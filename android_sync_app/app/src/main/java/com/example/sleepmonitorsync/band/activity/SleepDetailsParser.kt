package com.example.sleepmonitorsync.band.activity

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses Xiaomi sleep files (ACTIVITY_SLEEP / subtype 8).
 *
 * Gadgetbridge's Xiaomi SleepDetailsParser identifies the v4 SUMMARY form used by
 * Mi Band 9 Pro and reads the wake_count from the type=16 "Summary" packet.
 * We only keep the fields needed by sleepmon: bedtime, wake-up time and the number
 * of awakenings. Sleep stage durations are intentionally not imported.
 *
 * Full file layout:
 *   7-byte fileId + 1 padding + sleep header/optional samples + stage packets + CRC32.
 */
object SleepDetailsParser {
    data class SleepSummary(
        val bedTimeSeconds: Int,
        val wakeupTimeSeconds: Int,
        val sleepDurationMinutes: Int,
        val wakeCount: Int,
        val pulseAvgSleep: Int = 0,
        val rrIntervalCount: Int = 0,
        val rrPacketCount: Int = 0,
        val summaryPacketCount: Int = 0,
        val stagePacketCount: Int = 0,
    )

    fun parse(fileId: XiaomiActivityFileId, fileBytes: ByteArray): SleepSummary? {
        if (fileId.type != XiaomiActivityFileId.TYPE_ACTIVITY ||
            fileId.subtype != XiaomiActivityFileId.SUBTYPE_ACTIVITY_SLEEP ||
            fileId.version !in 1..4 ||
            fileBytes.size < 20
        ) return null

        val bodyEnd = fileBytes.size - 4
        if (bodyEnd <= 9) return null

        val buf = ByteBuffer.wrap(fileBytes, 0, bodyEnd).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 9) return null

        buf.position(7)
        val padding = buf.get()
        if (padding.toInt() != 0) return null

        val header = buf.get().toInt() and 0xFF
        buf.get() // isAwake
        val bedTime = buf.int
        val wakeupTime = buf.int

        if (fileId.version >= 4) {
            if (!buf.hasRemaining()) return null
            buf.get() // sleep quality
        }

        // These optional blocks are described by Gadgetbridge's SleepDetailsParser.
        // Their lengths are self-described by unit + count (+ firstRecordTime for v2+).
        val versionDependentFields = if (fileId.version >= 4) 1 else 0

        fun skipTimedByteSamples(bitIndex: Int, bytesPerSample: Int) {
            if (bitIndex < 0 || bitIndex > 7 || (header and (1 shl bitIndex)) == 0) return
            if (buf.remaining() < 4) throw IllegalArgumentException("short sleep sample header")
            buf.short // unit
            val count = buf.short.toInt() and 0xFFFF
            if (count > 0 && fileId.version >= 2) {
                if (buf.remaining() < 4) throw IllegalArgumentException("short sleep sample timestamp")
                buf.int
            }
            val bytes = count * bytesPerSample
            if (bytes > buf.remaining()) throw IllegalArgumentException("short sleep sample payload")
            buf.position(buf.position() + bytes)
        }

        return try {
            skipTimedByteSamples(5 - versionDependentFields, 1)
            skipTimedByteSamples(4 - versionDependentFields, 1)
            if (fileId.version >= 3) {
                skipTimedByteSamples(3 - versionDependentFields, 4)
            }

            var wakeCount = 0
            var sleepDurationMinutes = 0
            var rrPacketCount = 0
            var summaryPacketCount = 0
            var stagePacketCount = 0
            val rrIntervalsMs = mutableListOf<Int>()

            // Sleep stage packets are preceded by the fixed FF FC FA FB marker.
            while (buf.remaining() >= 17) {
                var markerFound = false
                while (buf.remaining() >= 4) {
                    val p = buf.position()
                    if ((buf.get(p).toInt() and 0xFF) == 0xFB &&
                        (buf.get(p + 1).toInt() and 0xFF) == 0xFA &&
                        (buf.get(p + 2).toInt() and 0xFF) == 0xFC &&
                        (buf.get(p + 3).toInt() and 0xFF) == 0xFF) {
                        markerFound = true
                        break
                    }
                    buf.position(p + 1)
                }
                if (!markerFound) break

                buf.position(buf.position() + 4)
                buf.get() // packet header length
                if (buf.remaining() < 12) break
                buf.long // timestamp
                buf.get() // parity
                val type = buf.get().toInt() and 0xFF
                // Xiaomi sleep packet dataLen is big-endian (unlike the surrounding packet fields).
                val dataLen = ((buf.get().toInt() and 0xFF) shl 8) or
                    (buf.get().toInt() and 0xFF)

                // These packet types carry no data bytes despite the nominal length fields.
                if (type == 0x2 || type == 0x3 || type == 0x9 || type == 0xc ||
                    type == 0xd || type == 0xe || type == 0xf) {
                    continue
                }

                if (dataLen > buf.remaining()) break
                val data = ByteArray(dataLen)
                buf.get(data)

                when {
                    type == 1 -> {
                        rrPacketCount++
                        // Xiaomi Band sleep pulse is stored as RR intervals in 10 ms units.
                        for (rawDelta in data) {
                            val intervalMs = (rawDelta.toInt() and 0xFF) * 10
                            if (intervalMs in 300..2000) {
                                rrIntervalsMs.add(intervalMs)
                            }
                        }
                    }
                    type == 0x10 && dataLen >= 13 -> {
                        // Summary payload is BIG-endian even though the outer file buffer is little-endian.
                        // Gadgetbridge parses these fields from a BIG_ENDIAN ByteBuffer.
                        summaryPacketCount++
                        wakeCount = data[0].toInt() and 0x0F
                        sleepDurationMinutes = ((data[1].toInt() and 0xFF) shl 8) or
                            (data[2].toInt() and 0xFF)
                    }
                    type == 0x11 -> {
                        stagePacketCount++
                    }
                }
            }

            if (sleepDurationMinutes == 0 && wakeupTime > bedTime) {
                sleepDurationMinutes = (wakeupTime - bedTime) / 60
            }
            val pulseAvgSleep = if (rrIntervalsMs.isNotEmpty()) {
                rrIntervalsMs.map { 60000.0 / it }.average().toInt()
            } else {
                0
            }
            SleepSummary(
                bedTimeSeconds = bedTime,
                wakeupTimeSeconds = wakeupTime,
                sleepDurationMinutes = sleepDurationMinutes,
                wakeCount = wakeCount,
                pulseAvgSleep = pulseAvgSleep,
                rrIntervalCount = rrIntervalsMs.size,
                rrPacketCount = rrPacketCount,
                summaryPacketCount = summaryPacketCount,
                stagePacketCount = stagePacketCount,
            )
        } catch (_: BufferUnderflowException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
