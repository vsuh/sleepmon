package com.example.sleepmonitorsync

import android.content.Context
import com.example.sleepmonitorsync.band.activity.ActivitySample
import java.io.File

/**
 * Persistent per-minute heart-rate history (last [KEEP_DAYS] days) plus known sleep windows.
 *
 * Why: the band sends each minute-details file only once (it is deleted after ACK), so a
 * single sync only sees the newest slice of the day. Averaging just that slice made
 * pulse_avg_day jump around. We keep every minute sample we have ever received, keyed by
 * timestamp (so re-sending the same sample is idempotent), and compute the whole-day waking
 * average from the union. Sleep windows are stored too, so night samples received before the
 * sleep file arrived are excluded as soon as the window becomes known.
 *
 * File format (plain text, one record per line): `S <ts> <bpm>` and `W <bedTs> <wakeTs>`.
 */
object HrHistory {
    private const val FILE_NAME = "hr_history.txt"
    private const val KEEP_DAYS = 7L
    private val lock = Any()
    private val TAG = AppVersion.logTag("HrHistory")

    class Snapshot(
        /** timestamp (unix seconds) -> bpm, only valid (> 0) samples */
        val hr: Map<Int, Int>,
        /** (bed, wake) pairs; one per night, the longest window wins for the same bed time */
        val sleepWindows: Set<Pair<Int, Int>>,
    ) {
        fun isAsleep(ts: Int): Boolean = sleepWindows.any { ts >= it.first && ts <= it.second }
    }

    /** Merges new samples/windows into the stored history, prunes old data, saves, returns the union. */
    fun mergeAndLoad(
        context: Context,
        samples: Collection<ActivitySample>,
        windows: Collection<Pair<Int, Int>>,
    ): Snapshot = synchronized(lock) {
        val file = File(context.filesDir, FILE_NAME)
        val hr = HashMap<Int, Int>()
        val win = HashSet<Pair<Int, Int>>()

        try {
            if (file.exists()) {
                file.forEachLine { line ->
                    val p = line.split(' ')
                    if (p.size == 3) {
                        val a = p[1].toIntOrNull()
                        val b = p[2].toIntOrNull()
                        if (a != null && b != null) {
                            when (p[0]) {
                                "S" -> hr[a] = b
                                "W" -> win.add(a to b)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            VersionedLog.w(TAG, "HR history read failed, continuing with current sync only: ${e.message}")
        }

        samples.forEach { s ->
            val bpm = s.heartRate
            if (bpm != null && bpm > 0) hr[s.timestampSeconds] = bpm
        }
        win.addAll(windows)

        val cutoff = (System.currentTimeMillis() / 1000 - KEEP_DAYS * 24 * 3600).toInt()
        hr.keys.removeAll { it < cutoff }
        val mergedWindows = win
            .filter { it.second >= cutoff }
            .groupBy { it.first }
            .map { (bed, list) -> bed to list.maxOf { it.second } }
            .toSet()

        try {
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.bufferedWriter().use { w ->
                mergedWindows.forEach { w.write("W ${it.first} ${it.second}\n") }
                hr.toSortedMap().forEach { (ts, bpm) -> w.write("S $ts $bpm\n") }
            }
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            VersionedLog.w(TAG, "HR history write failed (data for this sync is still used): ${e.message}")
        }

        Snapshot(hr, mergedWindows)
    }
}
