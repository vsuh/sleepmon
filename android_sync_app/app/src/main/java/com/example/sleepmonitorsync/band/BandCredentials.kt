package com.example.sleepmonitorsync.band

import android.content.Context
import android.content.SharedPreferences

/**
 * MAC address + auth key ("token") for the Xiaomi Smart Band 9 Pro, used to open a
 * direct BLE/Classic connection without going through Mi Fitness / Health Connect.
 *
 * The auth key was extracted via xiaomi-extractor (the same class of tool Gadgetbridge
 * points users to for "Huami/Xiaomi server pairing" - see
 * https://codeberg.org/Freeyourgadget/Gadgetbridge/wiki/Huami-Server-Pairing). It is a
 * long-lived pairing secret, not a session token, but Xiaomi rotates/invalidates it
 * whenever the device is unlinked/relinked in Mi Fitness (confirmed in practice - see
 * 10-projects/sleep-monitor/task.md, "auth key rotation" entries) - hence this is
 * stored in SharedPreferences rather than hardcoded, so it can be updated without a
 * rebuild if it ever stops working (verify with [DerivedAuthMaterial]-style offline
 * checks against a fresh HCI capture before assuming the key itself is the problem).
 *
 * TODO: move to EncryptedSharedPreferences (androidx.security.crypto) once that
 * dependency is pulled in - plain prefs are an acceptable stopgap for a single-user,
 * local-network app but not best practice for a long-lived device-pairing secret.
 */
data class BandCredentials(
    val macAddress: String,
    val authKeyHex: String,
) {
    /** 16-byte auth key, decoded from [authKeyHex]. */
    fun authKeyBytes(): ByteArray {
        val clean = authKeyHex.trim().removePrefix("0x").removePrefix("0X")
        require(clean.length == 32) { "authKeyHex must be 32 hex chars (16 bytes), got ${clean.length}" }
        return ByteArray(16) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    companion object {
        private const val PREFS_NAME = "xiaomi_band_prefs"
        private const val KEY_MAC = "mac_address"
        private const val KEY_AUTH_KEY = "auth_key_hex"

        private const val DEFAULT_MAC = "D0:AE:05:11:3D:FE"

        // Auth key history (most recent first) - see 10-projects/sleep-monitor/task.md:
        //   2026-09-14: b267f926cfe511ef21f5beaa986e6715 - re-extracted after the
        //     2026-08-29 key rotated (confirmed dead: verified offline against a real
        //     HCI-captured Mi Fitness session, only this newer key reproduces the
        //     device's real HMAC). Re-extract again if this one ever stops working -
        //     re-pairing/relinking in Mi Fitness rotates it.
        //   2026-08-29: c0bc3f6c7faaf56656fa5c6624fef35d - initial extraction, since
        //     rotated (kept here only for history/debugging reference).
        private const val DEFAULT_AUTH_KEY = "b267f926cfe511ef21f5beaa986e6715"

        private fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun load(context: Context): BandCredentials {
            val p = prefs(context)
            return BandCredentials(
                macAddress = p.getString(KEY_MAC, DEFAULT_MAC) ?: DEFAULT_MAC,
                authKeyHex = p.getString(KEY_AUTH_KEY, DEFAULT_AUTH_KEY) ?: DEFAULT_AUTH_KEY,
            )
        }

        /** Call this if the band gets re-paired and the auth key changes. */
        fun save(context: Context, credentials: BandCredentials) {
            prefs(context).edit()
                .putString(KEY_MAC, credentials.macAddress)
                .putString(KEY_AUTH_KEY, credentials.authKeyHex)
                .apply()
        }
    }
}
