package com.example.sleepmonitorsync.band.activity

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Xiaomi "miwear" activity file identifier - a 7-byte value that both identifies a
 * file the band offers to send, and (once decoded) tells us which parser to use.
 *
 * Layout (confirmed against real captured data - see
 * 10-projects/sleep-monitor/task.md, "Этап B" entries):
 *   [timestamp: u32 LE][timezone: u8][version: u8][packed: u8]
 * packed byte: bit7=type, bits2-6=subtype (5 bits), bits0-1=detailType (2 bits)
 *
 * Ported from Gadgetbridge's XiaomiActivityFileId
 * (https://codeberg.org/Freeyourgadget/Gadgetbridge, AGPLv3).
 */
data class XiaomiActivityFileId(
    val timestamp: Int, // unix seconds
    val timezone: Int,
    val version: Int,
    val type: Int,
    val subtype: Int,
    val detailType: Int,
) {
    val raw: ByteArray
        get() {
            val packed = ((type and 1) shl 7) or ((subtype and 0x1F) shl 2) or (detailType and 3)
            val buf = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(timestamp)
            buf.put(timezone.toByte())
            buf.put(version.toByte())
            buf.put(packed.toByte())
            return buf.array()
        }

    companion object {
        const val TYPE_ACTIVITY = 0
        const val TYPE_SPORTS = 1

        const val SUBTYPE_ACTIVITY_DAILY = 0
        const val SUBTYPE_ACTIVITY_SLEEP_STAGES = 3
        const val SUBTYPE_ACTIVITY_MANUAL_SAMPLES = 6
        const val SUBTYPE_ACTIVITY_SLEEP = 8

        const val DETAIL_TYPE_DETAILS = 0
        const val DETAIL_TYPE_SUMMARY = 1

        fun from(bytes: ByteArray): XiaomiActivityFileId {
            require(bytes.size == 7) { "fileId must be exactly 7 bytes, got ${bytes.size}" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val timestamp = buf.int
            val timezone = buf.get().toInt() and 0xFF
            val version = buf.get().toInt() and 0xFF
            val packed = buf.get().toInt() and 0xFF
            return XiaomiActivityFileId(
                timestamp = timestamp,
                timezone = timezone,
                version = version,
                type = (packed shr 7) and 1,
                subtype = (packed shr 2) and 0x1F,
                detailType = packed and 3,
            )
        }
    }
}
