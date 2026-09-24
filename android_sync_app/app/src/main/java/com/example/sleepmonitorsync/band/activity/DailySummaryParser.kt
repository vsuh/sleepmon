package com.example.sleepmonitorsync.band.activity

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One decoded daily-summary record (aggregated stats for a whole day). */
data class DailySummary(
    val timestampSeconds: Int,
    val steps: Int,
    val hrResting: Int,
    val hrMax: Int,
    val hrMin: Int,
    val hrAvg: Int,
    val stressAvg: Int,
    val stressMax: Int,
    val stressMin: Int,
    /** Bitmask, one bit per hour of the day (bit 0 = 00:00-01:00) marking hours the user stood up. */
    val standingHoursBitmask: Int,
    val calories: Int,
    val spo2Max: Int,
    val spo2Min: Int,
    val spo2Avg: Int,
)

/**
 * Parses `ACTIVITY_DAILY / SUMMARY` files (one aggregate record per day) - version 5
 * only (byte-aligned, unlike the bit-packed DailyDetailsParser). Ported from
 * Gadgetbridge's DailySummaryParser (https://codeberg.org/Freeyourgadget/Gadgetbridge,
 * AGPLv3), stripped of its Room/DB persistence.
 */
object DailySummaryParser {
    fun parse(fileId: XiaomiActivityFileId, fileBytes: ByteArray): DailySummary? {
        if (fileId.version != 5) return null // only version 5 is supported (matches upstream)

        val buf = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(7) // skip fileId
        buf.get() // padding, expected 0

        val headerSize = 4
        buf.position(buf.position() + headerSize) // skip header (unused)

        val steps = buf.int
        buf.get(); buf.get(); buf.get() // unk1/2/3
        val hrResting = buf.get().toInt() and 0xFF
        val hrMax = buf.get().toInt() and 0xFF
        buf.int // hrMaxTs (unused for now)
        val hrMin = buf.get().toInt() and 0xFF
        buf.int // hrMinTs (unused for now)
        val hrAvg = buf.get().toInt() and 0xFF
        val stressAvg = buf.get().toInt() and 0xFF
        val stressMax = buf.get().toInt() and 0xFF
        val stressMin = buf.get().toInt() and 0xFF
        val standingArr = ByteArray(3)
        buf.get(standingArr)
        val standing = (standingArr[0].toInt() and 0xFF) or
            ((standingArr[1].toInt() and 0xFF) shl 8) or
            ((standingArr[2].toInt() and 0xFF) shl 16)
        val calories = buf.short.toInt()
        buf.get(); buf.get(); buf.get() // unk7/8/9
        val spo2Max = buf.get().toInt() and 0xFF
        buf.int // spo2MaxTs (unused for now)
        val spo2Min = buf.get().toInt() and 0xFF
        buf.int // spo2MinTs (unused for now)
        val spo2Avg = buf.get().toInt() and 0xFF
        // trainingLoadDay/Week/Level, vitality fields follow but aren't needed yet.

        return DailySummary(
            timestampSeconds = fileId.timestamp,
            steps = steps,
            hrResting = hrResting,
            hrMax = hrMax,
            hrMin = hrMin,
            hrAvg = hrAvg,
            stressAvg = stressAvg,
            stressMax = stressMax,
            stressMin = stressMin,
            standingHoursBitmask = standing,
            calories = calories,
            spo2Max = spo2Max,
            spo2Min = spo2Min,
            spo2Avg = spo2Avg,
        )
    }
}
