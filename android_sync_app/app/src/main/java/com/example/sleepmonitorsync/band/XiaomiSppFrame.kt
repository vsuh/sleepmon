package com.example.sleepmonitorsync.band

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Frame format for the Xiaomi "miwear" Bluetooth Classic SPP transport (used instead of
 * BLE GATT by devices/firmware where the fe95 GATT service doesn't expose the usual
 * command characteristics - confirmed to be the case for this Mi Band 9 Pro, see
 * 10-projects/sleep-monitor/task.md).
 *
 * Ported from Gadgetbridge's XiaomiSppPacketV1
 * (https://codeberg.org/Freeyourgadget/Gadgetbridge, AGPLv3). Only protocol V1 framing
 * is implemented - if this device reports SPP protocol version >= 2 (first byte of the
 * version-channel response), Gadgetbridge switches to a V2 frame format we haven't
 * ported; see XiaomiBandClassicConnection's version-response handling.
 *
 * Frame layout (all multi-byte fields little-endian):
 *   [0xBA 0xDC 0xFE]   3-byte preamble
 *   [channel: u8]      low nibble = logical channel id (0=Version, 1/2=ProtoRX/TX, 3=Fitness, ...)
 *   [flags: u8]        bit7=flag, bit6=needsResponse
 *   [length: u16 LE]   payload.size + 3 (the 3 header bytes below are included)
 *   [opCode: u8]       0=READ, 2=SEND
 *   [frameSerial: u8]
 *   [dataType: u8]     0=PLAIN, 1=ENCRYPTED, 2=AUTH
 *   [payload: N bytes]
 *   [0xEF]             1-byte epilogue
 * Total frame size = length field + 8.
 */
object XiaomiSppFrame {
    val PREAMBLE = byteArrayOf(0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte())
    val EPILOGUE = byteArrayOf(0xEF.toByte())

    const val CHANNEL_VERSION = 0
    const val CHANNEL_PROTO_RX = 1
    const val CHANNEL_PROTO_TX = 2
    const val CHANNEL_FITNESS = 3

    const val DATA_TYPE_PLAIN = 0
    const val DATA_TYPE_ENCRYPTED = 1
    const val DATA_TYPE_AUTH = 2

    const val OPCODE_READ = 0
    const val OPCODE_SEND = 2

    data class Frame(
        val channelRaw: Int,
        val flag: Boolean,
        val needsResponse: Boolean,
        val opCode: Int,
        val frameSerial: Int,
        val dataType: Int,
        val payload: ByteArray,
    )

    fun encode(
        channelRaw: Int,
        payload: ByteArray,
        opCode: Int = OPCODE_SEND,
        frameSerial: Int = 0,
        dataType: Int = DATA_TYPE_PLAIN,
        flag: Boolean = true,
        needsResponse: Boolean = false,
    ): ByteArray {
        val buf = ByteBuffer.allocate(11 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(PREAMBLE)
        buf.put((channelRaw and 0x0F).toByte())
        buf.put(((if (flag) 0x80 else 0) or (if (needsResponse) 0x40 else 0)).toByte())
        buf.putShort((payload.size + 3).toShort())
        buf.put((opCode and 0xFF).toByte())
        buf.put((frameSerial and 0xFF).toByte())
        buf.put((dataType and 0xFF).toByte())
        buf.put(payload)
        buf.put(EPILOGUE)
        return buf.array()
    }

    /** Decodes a single frame; [packet] must be exactly one frame's worth of bytes (see [Assembler]). */
    private fun decode(packet: ByteArray): Frame? {
        if (packet.size < 11) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val preamble = ByteArray(3).also { buf.get(it) }
        if (!preamble.contentEquals(PREAMBLE)) return null
        val channelRaw = buf.get().toInt() and 0x0F
        val flagsByte = buf.get().toInt() and 0xFF
        val flag = (flagsByte and 0x80) != 0
        val needsResponse = (flagsByte and 0x40) != 0
        val payloadLen = (buf.short.toInt() and 0xFFFF) - 3
        if (payloadLen < 0 || payloadLen + 11 > packet.size) return null
        val opCode = buf.get().toInt() and 0xFF
        val frameSerial = buf.get().toInt() and 0xFF
        val dataType = buf.get().toInt() and 0xFF
        val payload = ByteArray(payloadLen).also { buf.get(it) }
        val epilogue = ByteArray(1).also { buf.get(it) }
        if (!epilogue.contentEquals(EPILOGUE)) return null
        return Frame(channelRaw, flag, needsResponse, opCode, frameSerial, dataType, payload)
    }

    /**
     * Accumulates raw bytes read from the RFCOMM `InputStream` (which delivers an
     * arbitrary byte stream, not aligned to frame boundaries) and extracts complete
     * frames as they become available. Not thread-safe - feed from a single reader
     * thread/coroutine only.
     */
    class Assembler {
        private var buffer = ByteArray(0)

        fun feed(data: ByteArray): List<Frame> {
            buffer += data
            val frames = mutableListOf<Frame>()
            while (true) {
                var start = -1
                var i = 0
                while (i <= buffer.size - 3) {
                    if (buffer[i] == PREAMBLE[0] && buffer[i + 1] == PREAMBLE[1] && buffer[i + 2] == PREAMBLE[2]) {
                        start = i
                        break
                    }
                    i++
                }
                if (start < 0) {
                    // No preamble found yet - keep the last 2 bytes in case they're the start of one, drop the rest.
                    if (buffer.size > 2) buffer = buffer.copyOfRange(buffer.size - 2, buffer.size)
                    break
                }
                if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
                if (buffer.size < 6) break // not enough for channel+flags+length yet

                val lengthField = (buffer[4].toInt() and 0xFF) or ((buffer[5].toInt() and 0xFF) shl 8)
                val totalLen = lengthField + 8
                if (totalLen < 11) {
                    // Bogus length following what looked like a preamble - skip 1 byte and keep scanning.
                    buffer = buffer.copyOfRange(1, buffer.size)
                    continue
                }
                if (buffer.size < totalLen) break // wait for the rest of this frame

                val packet = buffer.copyOfRange(0, totalLen)
                buffer = buffer.copyOfRange(totalLen, buffer.size)
                decode(packet)?.let { frames.add(it) }
                // If decode() returned null (bad epilogue etc.) we've already consumed the bytes; keep scanning.
            }
            return frames
        }
    }
}
