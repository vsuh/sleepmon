package com.example.sleepmonitorsync

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object SyncHelper {
    private const val TAG = "SyncHelper"
    private const val FALLBACK_TIMEOUT_SECONDS = 5L

    /**
     * Sync the last [days] days (relative to today, inclusive).
     * Kept for background periodic sync (SyncWorker) and the "Sync Now" button.
     */
    suspend fun performSync(
        client: HealthConnectClient,
        primaryUrl: String,
        backupUrl: String,
        pin: String,
        days: Int,
        onStatus: (String) -> Unit
    ) {
        val today = LocalDate.now()
        Log.i(TAG, "═══ Starting sync: last $days days (from ${today.minusDays(days.toLong())} to $today)")
        performSyncRange(client, primaryUrl, backupUrl, pin, today.minusDays(days.toLong()), today, onStatus)
    }

    /**
     * Sync an arbitrary inclusive date range [fromDate .. toDate].
     * Used by the manual "Sync Range" (backfill) button.
     *
     * Tries [primaryUrl] first; if it doesn't respond within [FALLBACK_TIMEOUT_SECONDS]
     * seconds (or fails outright), falls back to [backupUrl] for the whole sync run.
     */
    suspend fun performSyncRange(
        client: HealthConnectClient,
        primaryUrl: String,
        backupUrl: String,
        pin: String,
        fromDate: LocalDate,
        toDate: LocalDate,
        onStatus: (String) -> Unit
    ) {
        if (fromDate.isAfter(toDate)) {
            val msg = "❌ Error: 'from' date ($fromDate) is after 'to' date ($toDate)"
            Log.e(TAG, msg)
            onStatus(msg)
            return
        }

        try {
            Log.i(TAG, "Resolving active server (primary: $primaryUrl, backup: $backupUrl)")
            val (activeUrl, cookie) = resolveActiveServer(primaryUrl, backupUrl, pin, onStatus)
            Log.i(TAG, "✅ Connected to: $activeUrl")

            var current = fromDate
            var successCount = 0
            var errorCount = 0
            val totalDays = java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate) + 1

            while (!current.isAfter(toDate)) {
                val dayNum = successCount + errorCount + 1
                val progressMsg = "📅 Day $dayNum/$totalDays: syncing $current"
                Log.i(TAG, progressMsg)
                onStatus(progressMsg)
                try {
                    syncSingleDay(client, activeUrl, cookie, current)
                    successCount++
                    Log.i(TAG, "  ✅ $current synced successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "  ❌ Error syncing $current: ${e.message}", e)
                    errorCount++
                    onStatus("❌ Error on $current: ${e.localizedMessage}")
                }
                current = current.plusDays(1)
            }

            val finishMsg = "═══ Sync finished: $successCount ok, $errorCount errors"
            Log.i(TAG, finishMsg)
            onStatus(finishMsg)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fatal error during sync: ${e.message}", e)
            onStatus("❌ Error: ${e.localizedMessage}")
        }
    }

    /**
     * Tries [primaryUrl] first (with a short timeout), falls back to [backupUrl] on
     * timeout/failure. Returns the URL that worked plus the session cookie from login.
     * Throws if neither server is reachable.
     */
    private suspend fun resolveActiveServer(
        primaryUrl: String,
        backupUrl: String,
        pin: String,
        onStatus: (String) -> Unit
    ): Pair<String, String> {
        val candidates = listOf(primaryUrl, backupUrl).filter { it.isNotBlank() }
        var lastError: Exception? = null

        for ((idx, url) in candidates.withIndex()) {
            try {
                Log.d(TAG, "Attempting server ${idx + 1}/${candidates.size}: $url")
                val cookie = login(url, pin)
                Log.d(TAG, "✅ Login successful to $url")
                return url to cookie
            } catch (e: Exception) {
                Log.w(TAG, "❌ Server $url failed: ${e.message}")
                onStatus("⚠️ $url недоступен, пробую следующий...")
                lastError = e
            }
        }

        val errMsg = "❌ All servers failed: ${lastError?.message}"
        Log.e(TAG, errMsg)
        throw Exception(errMsg)
    }

    private suspend fun login(url: String, pin: String): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val okClient = OkHttpClient.Builder()
                .followRedirects(false)
                .connectTimeout(FALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(FALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(FALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            val loginBody = FormBody.Builder().add("pin", pin).build()
            val loginReq = Request.Builder().url("$url/login").post(loginBody).build()
            val loginResp = okClient.newCall(loginReq).execute()
            loginResp.header("Set-Cookie") ?: ""
        }

    private suspend fun syncSingleDay(
        client: HealthConnectClient,
        url: String,
        cookie: String,
        targetDay: LocalDate
    ) {
        Log.d(TAG, "↓ Fetching Health Connect data for $targetDay")
        
        val startOfDay = targetDay.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant()
        val endOfDay = targetDay.plusDays(1).atStartOfDay().atZone(ZoneId.systemDefault()).toInstant()

        // 1. Steps
        Log.d(TAG, "  📊 Reading steps...")
        val stepsRequest = AggregateRequest(
            metrics = setOf(StepsRecord.COUNT_TOTAL),
            timeRangeFilter = TimeRangeFilter.between(startOfDay, endOfDay)
        )
        val stepsResp = client.aggregate(stepsRequest)
        val totalSteps = stepsResp[StepsRecord.COUNT_TOTAL] ?: 0L
        Log.d(TAG, "  ✓ Steps: $totalSteps")

        // 2. Heart Rate
        Log.d(TAG, "  📊 Reading heart rate...")
        val hrRequest = AggregateRequest(
            metrics = setOf(HeartRateRecord.BPM_AVG),
            timeRangeFilter = TimeRangeFilter.between(startOfDay, endOfDay)
        )
        val hrResp = client.aggregate(hrRequest)
        val hrAvg = hrResp[HeartRateRecord.BPM_AVG] ?: 0L
        if (hrAvg > 0) {
            Log.d(TAG, "  ✓ Heart rate avg: $hrAvg BPM")
        } else {
            Log.w(TAG, "  ⚠ Heart rate: no data")
        }

        // 3. Sleep
        Log.d(TAG, "  📊 Reading sleep...")
        val sleepStart = targetDay.minusDays(1).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant()
        val sleepEnd = targetDay.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
        val sleepReq = ReadRecordsRequest(
            recordType = SleepSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(sleepStart, sleepEnd)
        )
        val sleepRecords = client.readRecords(sleepReq).records
        var sleepHours = 0.0
        if (sleepRecords.isNotEmpty()) {
            val longest = sleepRecords.maxByOrNull { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() }
            if (longest != null) {
                sleepHours = (longest.endTime.toEpochMilli() - longest.startTime.toEpochMilli()) / 3600000.0
                Log.d(TAG, "  ✓ Sleep: $sleepHours hours (${sleepRecords.size} sessions)")
            }
        } else {
            Log.w(TAG, "  ⚠ Sleep: no data")
        }

        Log.i(TAG, "📤 Posting to server: steps=$totalSteps, hr=$hrAvg BPM, sleep=$sleepHours h")
        postToServer(url, cookie, targetDay.toString(), sleepHours, hrAvg.toInt(), totalSteps.toInt())
    }

    /**
     * Posts to /sync (not /save): the server merges this into the existing note
     * and never touches alco/notes/well_being — those are user-owned fields that
     * only the web form's manual "Сохранить" (/save) is allowed to change.
     */
    private suspend fun postToServer(baseUrl: String, cookie: String, date: String, sleep: Double, hr: Int, steps: Int) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(FALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .readTimeout(FALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .callTimeout(FALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()

                val syncBody = FormBody.Builder()
                    .add("date", date)
                    .add("sleep_hours", sleep.toString())
                    .add("pulse_avg_day", hr.toString())
                    .add("pulse_avg_sleep", "0")
                    .add("steps_1", steps.toString())
                    .add("steps_2", "0")
                    .build()

                val syncReq = Request.Builder()
                    .url("$baseUrl/sync")
                    .addHeader("Cookie", cookie)
                    .post(syncBody)
                    .build()

                Log.d(TAG, "Sending POST to $baseUrl/sync for $date")
                val syncResp = client.newCall(syncReq).execute()
                
                if (syncResp.isSuccessful) {
                    Log.i(TAG, "✅ Server accepted data for $date (HTTP ${syncResp.code})")
                } else {
                    throw Exception("Server returned HTTP ${syncResp.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to post to server: ${e.message}", e)
                throw Exception("Failed to post data to server for $date: ${e.message}")
            }
        }
    }
}
