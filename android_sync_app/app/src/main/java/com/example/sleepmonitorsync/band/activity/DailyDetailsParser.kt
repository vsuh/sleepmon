package com.example.sleepmonitorsync.band.activity

/** One decoded per-minute activity sample. Fields are null when the band didn't report them for that minute. */
data class ActivitySample(
    val timestampSeconds: Int,
    val steps: Int? = null,
    val heartRate: Int? = null,
    val spo2: Int? = null,
    val stress: Int? = null,
)

/**
 * Parses `ACTIVITY_DAILY / DETAILS` files (per-minute steps/HR/spO2/stress samples) -
 * versions 1-4. Ported from Gadgetbridge's DailyDetailsParser
 * (https://codeberg.org/Freeyourgadget/Gadgetbridge, AGPLv3), stripped of its Room/DB
 * persistence (we return samples directly instead). Validated byte-for-byte against a
 * real captured file from this Mi Band 9 Pro (see 10-projects/sleep-monitor/task.md).
 *
 * [fileBytes] is the FULL reassembled file: 7-byte fileId + 1-byte padding + header +
 * per-minute records + trailing 4-byte CRC32 (already verified by the caller).
 */
object DailyDetailsParser {

    fun headerSizeForVersion(version: Int): Int? = when (version) {
        1, 2 -> 4
        3 -> 5
        4 -> 6
        else -> null // unsupported/unknown version
    }

    fun parse(fileId: XiaomiActivityFileId, fileBytes: ByteArray): List<ActivitySample>? {
        val headerSize = headerSizeForVersion(fileId.version) ?: return null

        val bodyEnd = fileBytes.size - 4 // discard trailing CRC32
        var pos = 7 // skip the 7-byte fileId
        val padding = fileBytes[pos]; pos += 1
        if (padding.toInt() != 0) {
            // Non-fatal: matches Gadgetbridge's own tolerant behaviour (log-only).
        }
        val header = fileBytes.copyOfRange(pos, pos + headerSize)
        pos += headerSize

        val version = fileId.version
        val parser = XiaomiComplexActivityParser(header, fileBytes, pos)
        val samples = mutableListOf<ActivitySample>()
        var minuteTs = fileId.timestamp

        while (parser.pos < bodyEnd) {
            parser.reset()
            var steps: Int? = null
            var heartRate: Int? = null
            var spo2: Int? = null
            var stress: Int? = null
            var includeExtraEntry = 0

            if (parser.nextGroup(16)) {
                if (parser.has(1)) includeExtraEntry = parser.get(1, 1)
                if (parser.has(2)) steps = parser.get(2, 14)
            }
            if (parser.nextGroup(8)) {
                // calories (unused for now) - parser.has(2) -> parser.get(2,6)
            }
            parser.nextGroup(8) // unknown
            parser.nextGroup(16) // distance (unused for now)
            if (parser.nextGroup(8)) {
                if (parser.has(0)) heartRate = parser.get(0, 8)
            }
            parser.nextGroup(8) // energy (unused for now)
            parser.nextGroup(16) // unknown

            if (version >= 3) {
                if (parser.nextGroup(8)) {
                    if (parser.has(0)) spo2 = parser.get(0, 8)
                }
                if (parser.nextGroup(8)) {
                    if (parser.has(0)) {
                        val s = parser.get(0, 8)
                        if (s != 255) stress = s
                    }
                }
            }
            if (includeExtraEntry == 1) {
                parser.nextGroup(8) // unknown
            }
            if (version >= 4) {
                parser.nextGroup(16) // light (unused for now)
                parser.nextGroup(16) // body momentum (unused for now)
            }

            samples.add(ActivitySample(minuteTs, steps, heartRate, spo2, stress))
            minuteTs += 60
        }

        return samples
    }
}
