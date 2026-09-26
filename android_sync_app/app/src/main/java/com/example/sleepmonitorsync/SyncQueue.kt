package com.example.sleepmonitorsync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent local outbox for Xiaomi data.
 *
 * The queue is written to app-private internal storage. A record stays here until
 * the server has accepted all queued days AND the corresponding band file IDs have
 * been ACKed. If the process/app dies between those operations, the record remains
 * and is safely retried on the next sync.
 */
object SyncQueue {
    data class PendingDay(
        val date: String,
        val sleepHours: Double,
        val pulseAvgDay: Int,
        val pulseAvgSleep: Int,
        val stepsTotal: Int,
        val sleepAwakenings: Int,
    )

    data class Pending(
        val days: List<PendingDay>,
        val fileIds: List<ByteArray>,
    )

    private const val FILE_NAME = "xiaomi_sync_queue.json"

    suspend fun load(context: Context): Pending = withContext(Dispatchers.IO) {
        synchronized(this@SyncQueue) {
            readLocked(context)
        }
    }

    suspend fun enqueue(
        context: Context,
        days: List<PendingDay>,
        fileIds: List<ByteArray>,
    ) = withContext(Dispatchers.IO) {
        synchronized(this@SyncQueue) {
            val old = readLocked(context)
            val mergedDays = (old.days + days).associateBy { it.date }.values.sortedBy { it.date }
            val mergedIds = (old.fileIds + fileIds)
                .distinctBy { it.toHex() }
            writeLocked(context, Pending(mergedDays, mergedIds))
        }
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        synchronized(this@SyncQueue) {
            File(context.filesDir, FILE_NAME).delete()
        }
    }

    private fun readLocked(context: Context): Pending {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return Pending(emptyList(), emptyList())
        return try {
            val root = JSONObject(file.readText())
            val daysJson = root.optJSONArray("days") ?: JSONArray()
            val idsJson = root.optJSONArray("file_ids") ?: JSONArray()

            val days = buildList {
                for (i in 0 until daysJson.length()) {
                    val d = daysJson.getJSONObject(i)
                    add(
                        PendingDay(
                            date = d.getString("date"),
                            sleepHours = d.optDouble("sleep_hours", 0.0),
                            pulseAvgDay = d.optInt("pulse_avg_day", 0),
                            pulseAvgSleep = d.optInt("pulse_avg_sleep", 0),
                            stepsTotal = d.optInt("steps_total", 0),
                            sleepAwakenings = d.optInt("sleep_awakenings", 0),
                        )
                    )
                }
            }

            val fileIds = buildList {
                for (i in 0 until idsJson.length()) {
                    add(hexToBytes(idsJson.getString(i)))
                }
            }
            Pending(days, fileIds)
        } catch (e: Exception) {
            // Never silently overwrite a malformed queue. Keep the file for diagnosis
            // and fail closed so the caller does not ACK band files by accident.
            throw IllegalStateException("Cannot read Xiaomi sync queue: ${e.message}", e)
        }
    }

    private fun writeLocked(context: Context, pending: Pending) {
        val target = File(context.filesDir, FILE_NAME)
        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        val root = JSONObject()
        val daysJson = JSONArray()
        pending.days.forEach { d ->
            daysJson.put(
                JSONObject()
                    .put("date", d.date)
                    .put("sleep_hours", d.sleepHours)
                    .put("pulse_avg_day", d.pulseAvgDay)
                    .put("pulse_avg_sleep", d.pulseAvgSleep)
                    .put("steps_total", d.stepsTotal)
                    .put("sleep_awakenings", d.sleepAwakenings)
            )
        }
        val idsJson = JSONArray()
        pending.fileIds.forEach { idsJson.put(it.toHex()) }
        root.put("days", daysJson)
        root.put("file_ids", idsJson)

        temp.writeText(root.toString())
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IllegalStateException("Cannot atomically replace Xiaomi sync queue")
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hexToBytes(value: String): ByteArray {
        require(value.length % 2 == 0) { "Invalid hex fileId length" }
        return ByteArray(value.length / 2) { i ->
            value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
