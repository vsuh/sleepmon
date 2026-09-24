package com.example.sleepmonitorsync.band

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Frame format for Xiaomi "miwear" Bluetooth Classic SPP transport, PROTOCOL VERSION 2.
 *
 * This is the version actually used by this specific Mi Band 9 Pro - confirmed by
 * capturing a real Mi Fitness <-> band session via Android's HCI snoop log and
 * decoding it byte-for-byte against Gadgetbridge's XiaomiSppPacketV2 source
 * (https://codeberg.org/Freeyourgadget/Gadgetbridge, AGPLv3). Every constant and the
 * CRC algorithm below were verified against real captured bytes (see
 * 10-projects/sleep-monitor/task.md, "V2 protocol confirmed via HCI snoop" entry) - not
 * guessed from source alone like the earlier V1 attempt.
 *
 * Frame layout (all multi-byte fields little-endian):
 *   [0xA5 0xA5]        2-byte preamble
 *   [packetType: u8]   low nibble: 1=ACK, 2=SESSION_CONFIG, 3=DATA
 *   [sequenceNumber: u8]
 *   [payloadLength: u16 LE]
 *   [checksum: u16 LE] CRC over payload only (see [crc16])
 *   [payload: N bytes]
 * Total frame size = 8 + payloadLength (no separate epilogue byte, unlike V1).
 *
 * DATA packet payload layout: [rawChannel: u8 (low nibble)] [opCode: u8] [inner bytes]
 *   rawChannel: 1=Protobuf (Authentication/ProtobufCommand), 2=Data, 5=Activity
 *   opCode: 1=send plaintext, 2=send encrypted (inner bytes are AES-CCM ciphertext)
 *
 * SESSION_CONFIG payload layout: [opCode: u8] + repeated [key: u8][size: u16 LE][value: size bytes]
 *   opCode: 1=start request, 2=start response, 3=stop request, 4=stop response
 *   keys: 1=VERSION(3 bytes), 2=MAX_PACKET_SIZE(2 bytes), 3=TX_WIN(2 bytes), 4=SEND_TIMEOUT(2 bytes)
 */
object XiaomiSppFrameV2 {
    val PREAMBLE = byteArrayOf(0xA5.toByte(), 0xA5.toByte())

    const val PACKET_TYPE_ACK = 1
    const val PACKET_TYPE_SESSION_CONFIG = 2
    const val PACKET_TYPE_DATA = 3

    const val CHANNEL_PROTOBUF = 1
    const val CHANNEL_DATA = 2
    const val CHANNEL_ACTIVITY = 5

    const val OPCODE_SEND_PLAINTEXT = 1
    const val OPCODE_SEND_ENCRYPTED = 2

    const val SESSION_OPCODE_START_REQUEST = 1
    const val SESSION_OPCODE_START_RESPONSE = 2
    const val SESSION_OPCODE_STOP_REQUEST = 3
    const val SESSION_OPCODE_STOP_RESPONSE = 4

    const val SESSION_KEY_VERSION = 1
    const val SESSION_KEY_MAX_PACKET_SIZE = 2
    const val SESSION_KEY_TX_WIN = 3
    const val SESSION_KEY_SEND_TIMEOUT = 4

    /**
     * CRC-16-like checksum, ported bit-for-bit from Gadgetbridge's
     * calculatePayloadChecksum (poly 0x8005, processed LSB-first per byte, then the
     * low 16 bits of the running register are bit-reversed for the final value).
     * Verified byte-for-byte against a real captured session-config packet.
     */
    fun crc16(payload: ByteArray): Int {
        var crc = 0
        for (b in payload) {
            for (j in 0 until 8) {
                crc = (crc shl 1)
                val bit = (b.toInt() shr j) and 1
                if ((((crc shr 16) and 1) xor bit) == 1) {
                    crc = crc xor 0x8005
                }
            }
        }
        // Equivalent to Java's Integer.reverse(crc) >>> 16: reverse all 32 bits, take the top 16.
        var reversed = 0
        for (i in 0 until 32) {
            if ((crc shr i) and 1 == 1) {
                reversed = reversed or (1 shl (31 - i))
            }
        }
        return (reversed ushr 16) and 0xFFFF
    }

    /** Builds the standard session-config request payload (verified against a real capture). */
    fun buildSessionStartRequestPayload(): ByteArray {
        val buf = ByteBuffer.allocate(1 + (1 + 2 + 3) + (1 + 2 + 2) + (1 + 2 + 2) + (1 + 2 + 2)).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(SESSION_OPCODE_START_REQUEST.toByte())
        buf.put(SESSION_KEY_VERSION.toByte()); buf.putShort(3); buf.put(byteArrayOf(1, 0, 0))
        buf.put(SESSION_KEY_MAX_PACKET_SIZE.toByte()); buf.putShort(2); buf.putShort(0xfc00.toShort())
        buf.put(SESSION_KEY_TX_WIN.toByte()); buf.putShort(2); buf.putShort(0x0020)
        buf.put(SESSION_KEY_SEND_TIMEOUT.toByte()); buf.putShort(2); buf.putShort(0x2710)
        return buf.array()
    }

    data class Frame(val packetType: Int, val sequenceNumber: Int, val payload: ByteArray)

    fun encode(packetType: Int, sequenceNumber: Int, payload: ByteArray): ByteArray {
        val checksum = crc16(payload)
        val buf = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(PREAMBLE)
        buf.put((packetType and 0xF).toByte())
        buf.put((sequenceNumber and 0xFF).toByte())
        buf.putShort(payload.size.toShort())
        buf.putShort(checksum.toShort())
        buf.put(payload)
        return buf.array()
    }

    private fun decode(packet: ByteArray): Frame? {
        if (packet.size < 8) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val preamble = ByteArray(2).also { buf.get(it) }
        if (!preamble.contentEquals(PREAMBLE)) return null
        val packetType = buf.get().toInt() and 0xF
        val seq = buf.get().toInt() and 0xFF
        val len = buf.short.toInt() and 0xFFFF
        val givenChecksum = buf.short.toInt() and 0xFFFF
        if (len + 8 > packet.size) return null
        val payload = ByteArray(len).also { buf.get(it) }
        val calc = crc16(payload)
        if (calc != givenChecksum) return null
        return Frame(packetType, seq, payload)
    }

    /** Accumulates bytes from the RFCOMM InputStream and extracts complete V2 frames. Not thread-safe. */
    class Assembler {
        private var buffer = ByteArray(0)

        fun feed(data: ByteArray): List<Frame> {
            buffer += data
            val frames = mutableListOf<Frame>()
            while (true) {
                var start = -1
                var i = 0
                while (i <= buffer.size - 2) {
                    if (buffer[i] == PREAMBLE[0] && buffer[i + 1] == PREAMBLE[1]) {
                        start = i
                        break
                    }
                    i++
                }
                if (start < 0) {
                    if (buffer.size > 1) buffer = buffer.copyOfRange(buffer.size - 1, buffer.size)
                    break
                }
                if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
                if (buffer.size < 8) break

                val lengthField = (buffer[4].toInt() and 0xFF) or ((buffer[5].toInt() and 0xFF) shl 8)
                val totalLen = lengthField + 8
                if (buffer.size < totalLen) break

                val packet = buffer.copyOfRange(0, totalLen)
                buffer = buffer.copyOfRange(totalLen, buffer.size)
                val frame = decode(packet)
                if (frame != null) {
                    frames.add(frame)
                } else {
                    // Bad checksum or malformed - already consumed, keep scanning from what's left.
                }
            }
            return frames
        }
    }
}
