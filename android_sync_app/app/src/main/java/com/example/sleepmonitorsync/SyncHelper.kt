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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import com.example.sleepmonitorsync.band.BandCredentials
import com.example.sleepmonitorsync.band.XiaomiBandClassicConnection
import com.example.sleepmonitorsync.band.activity.ActivitySample
import com.example.sleepmonitorsync.band.activity.SleepDetailsParser

object SyncHelper {
    /**
     * Fetches accumulated activity directly from the Xiaomi band over Classic SPP and
     * uploads the decoded daily aggregates to /sync.
     *
     * The band is the source of truth for steps/heart-rate in the new pipeline.
     * Sleep and sleep phases remain zero here until the corresponding Xiaomi activity
     * file types are reverse-engineered; the backend fill-once merge keeps any
     * already recorded sleep values intact.
     */
    suspend fun performBandSync(
        context: android.content.Context,
        primaryUrl: String,
        backupUrl: String,
        pin: String,
        onStatus: (String) -> Unit
    ): Boolean {
        return XiaomiBandClassicConnection.withExclusiveSppOperation {
            Log.i(TAG, "Xiaomi SPP operation lock acquired")
            val credentials = BandCredentials.load(context)
        val authKey = credentials.authKeyHex.trim().removePrefix("0x").removePrefix("0X")
        if (authKey.length != 32 || authKey.any { it.digitToIntOrNull(16) == null }) {
            val msg = "⚠️ Xiaomi auth key не задан. Откройте Настройки → Xiaomi Band и введите 32 hex-символа."
            Log.w(TAG, msg)
            onStatus(msg)
            return@withExclusiveSppOperation false
        }

        val connection = XiaomiBandClassicConnection(context, credentials)
        try {
            onStatus("🔗 Подключаюсь к Xiaomi Band...")
            val auth = connection.authenticate()
            if (auth.isFailure) {
                val msg = "❌ Xiaomi auth: ${auth.exceptionOrNull()?.message}"
                Log.e(TAG, msg, auth.exceptionOrNull())
                onStatus(msg)
                return@withExclusiveSppOperation false
            }

            onStatus("📥 Загружаю накопившиеся данные с браслета...")
            val fetch = connection.fetchActivityData()
            if (fetch.isFailure) {
                val msg = "❌ Xiaomi fetch: ${fetch.exceptionOrNull()?.message}"
                Log.e(TAG, msg, fetch.exceptionOrNull())
                onStatus(msg)
                return@withExclusiveSppOperation false
            }

            val result = fetch.getOrThrow()
            onStatus("📊 Получено файлов: ${result.filesReceived}, минутных записей: ${result.perMinuteSamples.size}")

            val days = aggregateBandSamples(result.perMinuteSamples, result.dailySummaries, result.sleepSummaries)
            if (days.isNotEmpty()) {
                SyncQueue.enqueue(
                    context,
                    days.map { day ->
                        SyncQueue.PendingDay(
                            date = day.date.toString(),
                            sleepHours = day.sleepHours,
                            pulseAvgDay = day.pulseAvgDay,
                            pulseAvgSleep = day.pulseAvgSleep,
                            stepsTotal = day.stepsTotal,
                            sleepAwakenings = day.sleepAwakenings,
                        )
                    },
                    connection.pendingAckFileIds(),
                )
                onStatus("💾 Локальная очередь: сохранено ${days.size} дн.")
            }

            val pending = SyncQueue.load(context)
            if (pending.days.isEmpty()) {
                onStatus("ℹ️ Нет данных для отправки на сервер")
                return@withExclusiveSppOperation true
            }

            val (activeUrl, cookie) = resolveActiveServer(primaryUrl, backupUrl, pin, onStatus)
            for (day in pending.days.sortedBy { it.date }) {
                postToServer(activeUrl, cookie, day.date, day.sleepHours, day.pulseAvgDay,
                    day.pulseAvgSleep, day.stepsTotal, day.sleepAwakenings)
                onStatus("✅ ${day.date}: шаги ${day.stepsTotal}, пульс ${day.pulseAvgDay}")
            }

            if (!connection.acknowledgeFileIds(pending.fileIds)) {
                onStatus("⚠️ Сервер принял данные, но ACK браслету не удалось; локальная очередь сохранена для повтора.")
                return@withExclusiveSppOperation false
            }

            SyncQueue.clear(context)
            onStatus("═══ Xiaomi sync завершён: ${pending.days.size} дн.; очередь очищена")
            return@withExclusiveSppOperation true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Xiaomi sync failed: ${e.message}", e)
            onStatus("❌ Xiaomi sync: ${e.localizedMessage}")
            return@withExclusiveSppOperation false
        } finally {
            connection.disconnect()
            }
        }
    }

    private data class BandDayAggregate(
        val date: LocalDate,
        val stepsTotal: Int,
        val pulseAvgDay: Int,
        val pulseAvgSleep: Int = 0,
        val sleepHours: Double = 0.0,
        val sleepAwakenings: Int = 0,
    )

    private fun aggregateBandSamples(
        samples: List<ActivitySample>,
        summaries: List<com.example.sleepmonitorsync.band.activity.DailySummary>,
        sleepSummaries: List<SleepDetailsParser.SleepSummary>,
    ): List<BandDayAggregate> {
        val unique = samples.groupBy { it.timestampSeconds }
            .mapValues { (_, sameMinute) ->
                sameMinute.firstOrNull { it.steps != null || it.heartRate != null } ?: sameMinute.first()
            }
            .values

        val byDay = unique.groupBy {
            Instant.ofEpochSecond(it.timestampSeconds.toLong()).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        val summaryByDay = summaries.associateBy {
            Instant.ofEpochSecond(it.timestampSeconds.toLong()).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        // Sleep belongs to the local date on which the user woke up.
        val sleepByDay = sleepSummaries.associateBy {
            Instant.ofEpochSecond(it.wakeupTimeSeconds.toLong()).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        // A daily summary is an authoritative whole-day step counter. Some real band
        // syncs provide a summary file even when the per-minute details contain no
        // usable step samples, so include summary-only dates and use summary.steps as
        // the fallback instead of silently uploading zero steps.
        val dates = (byDay.keys + summaryByDay.keys + sleepByDay.keys).toSortedSet()

        return dates.map { date ->
            val daySamples = byDay[date].orEmpty()
            val sleep = sleepByDay[date]
            val sampledTotalSteps = daySamples.sumOf { it.steps ?: 0 }
            val summary = summaryByDay[date]
            // The Xiaomi daily summary is the authoritative whole-day counter.
            // Minute details may contain only partial/duplicate slices, so they
            // must not override the summary when it is available.
            val stepsTotal = summary?.steps?.coerceAtLeast(0) ?: sampledTotalSteps
            val hr = daySamples.mapNotNull { it.heartRate?.takeIf { bpm -> bpm > 0 } }
            val pulse = if (hr.isNotEmpty()) hr.average().toInt() else (summary?.hrAvg ?: 0)

            BandDayAggregate(
                date = date,
                stepsTotal = stepsTotal,
                pulseAvgDay = pulse,
                sleepHours = sleep?.sleepDurationMinutes?.div(60.0) ?: 0.0,
                sleepAwakenings = sleep?.wakeCount ?: 0,
            )
        }
    }
    private const val TAG = "SleepMonitor-v37/SyncHelper"
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

    /**
     * Average heart rate over [start, end). Returns 0 if there's no data
     * in that window (e.g. no sleep session found, or genuinely no samples).
     */
    private suspend fun avgHeartRate(
        client: HealthConnectClient,
        start: Instant,
        end: Instant
    ): Long {
        if (!start.isBefore(end)) return 0L
        val hrRequest = AggregateRequest(
            metrics = setOf(HeartRateRecord.BPM_AVG),
            timeRangeFilter = TimeRangeFilter.between(start, end)
        )
        val hrResp = client.aggregate(hrRequest)
        return hrResp[HeartRateRecord.BPM_AVG] ?: 0L
    }

    /** Count distinct awakening episodes inside one sleep session. */
    private fun countSleepAwakenings(session: SleepSessionRecord): Int {
        var count = 0
        var inAwakeEpisode = false

        for (stage in session.stages.sortedBy { it.startTime }) {
            val isAwake = when (stage.stage) {
                SleepSessionRecord.STAGE_TYPE_AWAKE,
                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> true
                else -> false
            }
            if (isAwake && !inAwakeEpisode) count++
            inAwakeEpisode = isAwake
        }
        return count
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

        // 2. Sleep — read BEFORE heart rate, so we know the wake/sleep window
        // and can split heart rate into "waking hours" vs "sleep" averages
        // instead of one flat whole-calendar-day average (which is dragged
        // down by naturally-lower overnight readings and doesn't match what
        // Mi Fitness shows as "average pulse"). Also counts distinct
        // awakening episodes inside the same session.
        Log.d(TAG, "  📊 Reading sleep...")
        val sleepSearchStart = targetDay.minusDays(1).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant()
        val sleepSearchEnd = targetDay.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
        val sleepReq = ReadRecordsRequest(
            recordType = SleepSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(sleepSearchStart, sleepSearchEnd)
        )
        val sleepRecords = client.readRecords(sleepReq).records
        var sleepHours = 0.0
        var sleepStart: Instant? = null
        var wakeTime: Instant? = null
        var sleepAwakenings = 0
        if (sleepRecords.isNotEmpty()) {
            val longest = sleepRecords.maxByOrNull { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() }
            if (longest != null) {
                sleepStart = longest.startTime
                wakeTime = longest.endTime
                sleepHours = (longest.endTime.toEpochMilli() - longest.startTime.toEpochMilli()) / 3600000.0
                Log.d(TAG, "  ✓ Sleep: $sleepHours hours (${sleepRecords.size} sessions, wake time: $wakeTime)")

                sleepAwakenings = countSleepAwakenings(longest)
                Log.d(TAG, "  ✓ Sleep awakenings: $sleepAwakenings")
            }
        } else {
            Log.w(TAG, "  ⚠ Sleep: no data")
        }

        // 3. Heart rate — split into "day" (waking hours only) and "sleep" averages.
        Log.d(TAG, "  📊 Reading heart rate...")

        // Waking-hours window: from wake time to end of day. Falls back to the
        // whole calendar day if we couldn't determine a wake time (no sleep
        // session found) — better a slightly-off number than none at all.
        val wakingStart = wakeTime ?: startOfDay
        val hrDayAvg = avgHeartRate(client, wakingStart, endOfDay)
        if (hrDayAvg > 0) {
            Log.d(TAG, "  ✓ Heart rate (waking hours, $wakingStart .. $endOfDay): $hrDayAvg BPM")
        } else {
            Log.w(TAG, "  ⚠ Heart rate (waking hours): no data")
        }

        // Sleep window: only meaningful if we found a sleep session.
        val hrSleepAvg = if (sleepStart != null && wakeTime != null) {
            val v = avgHeartRate(client, sleepStart, wakeTime)
            if (v > 0) {
                Log.d(TAG, "  ✓ Heart rate (sleep, $sleepStart .. $wakeTime): $v BPM")
            } else {
                Log.w(TAG, "  ⚠ Heart rate (sleep window): no data")
            }
            v
        } else {
            Log.w(TAG, "  ⚠ Heart rate (sleep): skipped, no sleep session")
            0L
        }

        Log.i(TAG, "📤 Posting to server: steps=$totalSteps, hr_day=$hrDayAvg BPM, hr_sleep=$hrSleepAvg BPM, " +
                "sleep=$sleepHours h, awakenings=$sleepAwakenings")
        postToServer(
            url, cookie, targetDay.toString(), sleepHours, hrDayAvg.toInt(), hrSleepAvg.toInt(),
            totalSteps.toInt(), sleepAwakenings
        )
    }

    /**
     * Posts to /sync (not /save): the server merges this into the existing note
     * and never touches alco/notes/well_being — those are user-owned fields that
     * only the web form's manual "Сохранить" (/save) is allowed to change.
     */
    private suspend fun postToServer(
        baseUrl: String,
        cookie: String,
        date: String,
        sleep: Double,
        hrDay: Int,
        hrSleep: Int,
        stepsTotal: Int,
        sleepAwakenings: Int
    ) {
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
                    .add("pulse_avg_day", hrDay.toString())
                    .add("pulse_avg_sleep", hrSleep.toString())
                    .add("steps_total", stepsTotal.toString())
                    .add("sleep_awakenings", sleepAwakenings.toString())
                    .build()

                val syncReq = Request.Builder()
                    .url("$baseUrl/sync")
                    .addHeader("Cookie", cookie)
                    .post(syncBody)
                    .build()

                Log.d(TAG, "Sending POST to $baseUrl/sync for $date")
                val syncResp = client.newCall(syncReq).execute()

                if (syncResp.isSuccessful) {
                    val responseBody = syncResp.body?.string().orEmpty()
                    Log.i(TAG, "✅ Server accepted data for $date (HTTP ${syncResp.code}): $responseBody")
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
