package com.example.sleepmonitorsync.band

import org.bouncycastle.crypto.CryptoException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Auth-handshake key derivation and AES-CCM framing for Xiaomi "miwear" BLE devices
 * (encrypted family: Mi Band 8/9, Watch S1 (Active), Redmi Watch 3 Active, etc).
 *
 * This is a from-scratch Kotlin re-implementation of the algorithm used by
 * Gadgetbridge's XiaomiAuthService (https://codeberg.org/Freeyourgadget/Gadgetbridge,
 * AGPLv3) - same cryptographic construction, rewritten rather than copy-pasted so it
 * fits this project's style, but the algorithm itself (HMAC-SHA256 key stretch with the
 * "miwear-auth" domain string + AES-CCM) is dictated by the device firmware, not by us.
 */
object XiaomiCrypto {

    private const val HKDF_LABEL = "miwear-auth"
    private const val HKDF_OUTPUT_LEN = 64 // decryptionKey(16) + encryptionKey(16) + decryptionNonce(4) + encryptionNonce(4)

    /** Result of the auth-step-2 key derivation. */
    data class AuthMaterial(
        val decryptionKey: ByteArray,
        val encryptionKey: ByteArray,
        val decryptionNonce: ByteArray,
        val encryptionNonce: ByteArray,
    )

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * Derives decryption/encryption keys + nonces from the pairing [secretKey] (the
     * "auth key"/"token") and the two nonces exchanged in auth step 1/2.
     *
     * This is a manual HKDF-like expansion: an inner HMAC keyed by (phoneNonce ||
     * watchNonce) is used to re-key [secretKey] into a per-session key, which is then
     * used to repeatedly HMAC "miwear-auth" + an incrementing counter byte until 64
     * bytes of keystream have been produced.
     */
    fun deriveAuthMaterial(secretKey: ByteArray, phoneNonce: ByteArray, watchNonce: ByteArray): AuthMaterial {
        val innerMacKey = SecretKeySpec(phoneNonce + watchNonce, "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(innerMacKey)
        val sessionKeyBytes = mac.doFinal(secretKey)
        mac.init(SecretKeySpec(sessionKeyBytes, "HmacSHA256"))

        val labelBytes = HKDF_LABEL.toByteArray(Charsets.US_ASCII)
        val output = ByteArray(HKDF_OUTPUT_LEN)
        var block = ByteArray(0)
        var counter: Byte = 1
        var written = 0
        while (written < output.size) {
            mac.reset()
            mac.update(block)
            mac.update(labelBytes)
            mac.update(counter)
            block = mac.doFinal()
            val toCopy = minOf(block.size, output.size - written)
            System.arraycopy(block, 0, output, written, toCopy)
            written += toCopy
            counter++
        }

        return AuthMaterial(
            decryptionKey = output.copyOfRange(0, 16),
            encryptionKey = output.copyOfRange(16, 32),
            decryptionNonce = output.copyOfRange(32, 36),
            encryptionNonce = output.copyOfRange(36, 40),
        )
    }

    /**
     * HMAC the watch is expected to have sent back to confirm it derived the same
     * [AuthMaterial.decryptionKey]. Compare against the watch's `WatchNonce.hmac`.
     */
    fun watchConfirmationHmac(decryptionKey: ByteArray, watchNonce: ByteArray, phoneNonce: ByteArray): ByteArray =
        hmacSha256(decryptionKey, watchNonce + phoneNonce)

    /** HMAC sent to the watch as `AuthStep3.encryptedNonces` (misleadingly named - it's an HMAC, not AES output). */
    fun phoneConfirmationHmac(encryptionKey: ByteArray, phoneNonce: ByteArray, watchNonce: ByteArray): ByteArray =
        hmacSha256(encryptionKey, phoneNonce + watchNonce)

    /** 12-byte AES-CCM nonce: 4-byte per-session nonce + 4 zero bytes + 4-byte little-endian packet counter. */
    fun packetNonce(base: ByteArray, counter: Int): ByteArray {
        require(base.size == 4) { "base nonce must be 4 bytes" }
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(base)
            .putInt(0)
            .putInt(counter)
            .array()
    }

    fun aesCcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, macSizeBits: Int = 32): ByteArray {
        val cipher = ccmCipher(forEncrypt = true, key = key, nonce = nonce, macSizeBits = macSizeBits)
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        try {
            cipher.doFinal(out, len)
        } catch (e: CryptoException) {
            throw IllegalStateException("AES-CCM encrypt failed", e)
        }
        return out
    }

    fun aesCcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, macSizeBits: Int = 32): ByteArray {
        val cipher = ccmCipher(forEncrypt = false, key = key, nonce = nonce, macSizeBits = macSizeBits)
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        val len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        try {
            cipher.doFinal(out, len)
        } catch (e: CryptoException) {
            throw IllegalStateException("AES-CCM decrypt failed (wrong key, or corrupted/replayed packet)", e)
        }
        return out
    }

    private fun ccmCipher(forEncrypt: Boolean, key: ByteArray, nonce: ByteArray, macSizeBits: Int): CCMBlockCipher {
        // Plain constructors (rather than the AESEngine.newInstance()/CCMBlockCipher.newInstance()
        // factories added in newer BC releases) for compatibility across the bcprov-jdk18on 1.78.x line -
        // these have been stable since early BC versions and are what Gadgetbridge itself uses.
        val engine = AESEngine()
        val cipher = CCMBlockCipher(engine)
        cipher.init(forEncrypt, AEADParameters(KeyParameter(key), macSizeBits, nonce, null))
        return cipher
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = Arrays.equals(a, b)

    /**
     * V2-protocol (SPP) encryption: AES-CTR with the 16-byte key ALSO used directly as
     * the CTR IV (no separate nonce/counter field at all). This is exactly what
     * Gadgetbridge's XiaomiAuthService.encryptV2/decryptV2 do - the upstream source
     * comment literally reads "I wish I was kidding" about this construction. Verified
     * byte-for-byte against a real captured device session (see
     * 10-projects/sleep-monitor/task.md, "Этап B" entries) - every message in that
     * capture decrypted to a clean, valid protobuf/activity-file structure.
     *
     * Encryption and decryption are the same operation (XOR against the same
     * keystream), so both directions call this one function.
     */
    fun ctrCryptV2(key: ByteArray, data: ByteArray): ByteArray {
        val engine = AESEngine()
        val cipher = SICBlockCipher(engine) // CTR mode
        cipher.init(true, ParametersWithIV(KeyParameter(key), key))
        val out = ByteArray(data.size)
        cipher.processBytes(data, 0, data.size, out, 0)
        return out
    }
}
