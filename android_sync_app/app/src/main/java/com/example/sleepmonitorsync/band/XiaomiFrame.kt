package com.example.sleepmonitorsync.band

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-level packet framing for the Xiaomi command-read/command-write GATT
 * characteristics (UUIDs ending `...0051` / `...0052`). Every write/notification on
 * these characteristics starts with a 2-byte little-endian "chunk index" (0 = single,
 * unchunked packet - the only case this file handles) followed by a 1-byte frame type.
 *
 * Only the "single command" path is implemented (frame type 2) plus its ack (type 3).
 * Frame types 0/1 (chunked transfer start/ack) are needed for large payloads (e.g.
 * bulk activity-history files) and are intentionally not handled yet - see
 * 10-projects/sleep-monitor/task.md, Этап B.
 *
 * Structure ported from Gadgetbridge's XiaomiCharacteristic
 * (https://codeberg.org/Freeyourgadget/Gadgetbridge, AGPLv3); the wire format itself is
 * dictated by the device firmware.
 */
object XiaomiFrame {

    /** Bytes to write back after receiving a [Frame.SingleCommand]: chunk=0, type=3 (ack), result=0. */
    val ACK: ByteArray = byteArrayOf(0, 0, 3, 0)

    sealed class Frame {
        /** type=3: acknowledgement for something *we* sent. [result] 0 = success. */
        data class Ack(val result: Int) : Frame()

        /** type=2: a single (unchunked) command from the watch. */
        data class SingleCommand(val encrypted: Boolean, val payload: ByteArray) : Frame()

        /** type=0/1: chunked transfer - not implemented yet. */
        data class Unsupported(val type: Int) : Frame()
    }

    /**
     * Builds a single (unchunked) command frame to write to the command-write
     * characteristic.
     *
     * @param commandBytes serialized `XiaomiProto.Command`, already AES-CCM-encrypted
     *   by the caller if [encrypted] is true.
     * @param nonceIndex required (and prepended, 2 bytes little-endian) iff [encrypted].
     */
    fun encodeSingle(commandBytes: ByteArray, encrypted: Boolean, nonceIndex: Int? = null): ByteArray {
        require(!encrypted || nonceIndex != null) { "encrypted frames need a nonceIndex" }
        val headerSize = if (encrypted) 6 else 4
        val buf = ByteBuffer.allocate(headerSize + commandBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0) // chunk index 0 = unchunked
        buf.put(2.toByte()) // type 2 = single command
        buf.put((if (encrypted) 1 else 2).toByte()) // 1 = encrypted payload follows, 2 = plaintext
        if (encrypted) {
            buf.putShort(nonceIndex!!.toShort())
        }
        buf.put(commandBytes)
        return buf.array()
    }

    /** Parses a notification received on the command-read characteristic. */
    fun decode(value: ByteArray): Frame {
        val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val chunk = buf.short.toInt() and 0xFFFF
        if (chunk != 0) {
            // Chunked packet - not implemented (see class doc).
            return Frame.Unsupported(-1)
        }
        val type = buf.get().toInt() and 0xFF
        return when (type) {
            2 -> {
                val encryptedFlag = buf.get().toInt() and 0xFF
                val payload = ByteArray(buf.remaining())
                buf.get(payload)
                Frame.SingleCommand(encrypted = encryptedFlag == 1, payload = payload)
            }
            3 -> {
                val result = buf.get().toInt() and 0xFF
                Frame.Ack(result)
            }
            else -> Frame.Unsupported(type)
        }
    }
}
