package com.example.sleepmonitorsync

import com.example.sleepmonitorsync.VersionedLog

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import org.json.JSONObject
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
     * The direct Xiaomi SPP path also requests the band's "past" activity set after
     * "today"; the returned subtype=8 sleep record is mapped to its local wake-up date
     * and contributes sleep duration, awakenings, and sleep-window heart rate.
     */
    suspend fun performBandSync(
        context: android.content.Context,
        primaryUrl: String,
        backupUrl: String,
        pin: String,
        onStatus: (String) -> Unit
    ): Boolean {
        return XiaomiBandClassicConnection.withExclusiveSppOperation {
            VersionedLog.i(TAG, "=== " + AppVersion.buildTag("HCI-bound sleep past-fetch; diagnostics") + " ===")
            VersionedLog.i(TAG, "Xiaomi SPP operation lock acquired")
            val credentials = BandCredentials.load(context)
        val authKey = credentials.authKeyHex.trim().removePrefix("0x").removePrefix("0X")
        if (authKey.length != 32 || authKey.any { it.digitToIntOrNull(16) == null }) {
            val msg = "⚠️ Xiaomi auth key не задан. Откройте Настройки → Xiaomi Band и введите 32 hex-символа."
            VersionedLog.w(TAG, msg)
            onStatus(msg)
            return@withExclusiveSppOperation false
        }

        val connection = XiaomiBandClassicConnection(context, credentials)
        try {
            onStatus("🔗 Подключаюсь к Xiaomi Band...")
            val auth = connection.authenticate()
            if (auth.isFailure) {
                val msg = "❌ Xiaomi auth: ${auth.exceptionOrNull()?.message}"
                VersionedLog.e(TAG, msg, auth.exceptionOrNull())
                onStatus(msg)
                return@withExclusiveSppOperation false
            }

            onStatus("📥 Загружаю накопившиеся данные с браслета...")
            val fetch = connection.fetchActivityData()
            if (fetch.isFailure) {
                val msg = "❌ Xiaomi fetch: ${fetch.exceptionOrNull()?.message}"
                VersionedLog.e(TAG, msg, fetch.exceptionOrNull())
                onStatus(msg)
                return@withExclusiveSppOperation false
            }

            val result = fetch.getOrThrow()
            VersionedLog.i(TAG, "Xiaomi fetch result: received=${result.filesReceived}, failed=${result.filesFailed}, unsupported=${result.filesUnsupported}, sleep=${result.sleepSummaries.size}, minuteSamples=${result.perMinuteSamples.size}, dailySummaries=${result.dailySummaries.size}")
            if (result.unsupportedFileDescriptions.isNotEmpty()) {
                VersionedLog.w(TAG, "Unsupported activity files: ${result.unsupportedFileDescriptions.size}")
            }
            onStatus("📊 Получено файлов: ${result.filesReceived}, минутных записей: ${result.perMinuteSamples.size}, daily-summary: ${result.dailySummaries.size}, sleep-файлов: ${result.sleepSummaries.size}, unsupported: ${result.filesUnsupported}")

            val days = aggregateBandSamples(context, result.perMinuteSamples, result.dailySummaries, result.sleepSummaries)
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
            val successfulDays = mutableListOf<SyncQueue.PendingDay>()
            for (day in pending.days.sortedBy { it.date }) {
                val saved = postToServer(activeUrl, cookie, day.date, day.sleepHours, day.pulseAvgDay,
                    day.pulseAvgSleep, day.stepsTotal, day.sleepAwakenings)
                successfulDays += SyncQueue.PendingDay(
                    date = saved.date,
                    sleepHours = saved.sleepHours,
                    pulseAvgDay = saved.pulseAvgDay,
                    pulseAvgSleep = saved.pulseAvgSleep,
                    stepsTotal = saved.stepsTotal,
                    sleepAwakenings = saved.sleepAwakenings,
                )
                onStatus("✅ ${day.date}: шаги ${saved.stepsTotal}, пульс ${saved.pulseAvgDay}")
            }

            if (!connection.acknowledgeFileIds(pending.fileIds)) {
                onStatus("⚠️ Сервер принял данные, но ACK браслету не удалось; локальная очередь сохранена для повтора.")
                return@withExclusiveSppOperation false
            }

            SyncHistory.record(context, successfulDays)
            SyncQueue.clear(context)
            onStatus("═══ Xiaomi sync завершён: ${pending.days.size} дн.; очередь очищена")
            return@withExclusiveSppOperation true
        } catch (e: Exception) {
            VersionedLog.e(TAG, "❌ Xiaomi sync failed: ${e.message}", e)
            onStatus("❌ Xiaomi sync: ${e.localizedMessage}")
            return@withExclusiveSppOperation false
        } finally {
            connection.disconnect()
            VersionedLog.i(TAG, "═══ Xiaomi sync session finished")
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
        context: android.content.Context,
        samples: List<ActivitySample>,
        summaries: List<com.example.sleepmonitorsync.band.activity.DailySummary>,
        sleepSummaries: List<SleepDetailsParser.SleepSummary>,
    ): List<BandDayAggregate> {
        val unique = samples.groupBy { it.timestampSeconds }
            .mapValues { (_, sameMinute) ->
                sameMinute.firstOrNull { it.steps != null || it.heartRate != null } ?: sameMinute.first()
            }
            .values

        // Whole-day waking pulse needs the WHOLE day, but the band sends each minute file once.
        // HrHistory keeps every sample we ever received (idempotent by timestamp) plus the known
        // sleep windows, so the average below does not depend on what this particular sync fetched.
        val hrHistory = HrHistory.mergeAndLoad(
            context,
            unique,
            sleepSummaries.map { it.bedTimeSeconds to it.wakeupTimeSeconds },
        )
        val hrHistoryByDay = hrHistory.hr.entries.groupBy {
            Instant.ofEpochSecond(it.key.toLong()).atZone(ZoneId.systemDefault()).toLocalDate()
        }

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
            // pulse_avg_day = waking HR over the whole day: every stored minute sample of this
            // date that is not inside any known sleep window (a window can start the day before).
            val dayHr = hrHistoryByDay[date].orEmpty()
            val awakeHr = dayHr.filter { !hrHistory.isAsleep(it.key) }.map { it.value }
            val pulse = when {
                awakeHr.isNotEmpty() -> awakeHr.average().toInt()
                dayHr.isNotEmpty() -> 0 // only night data so far: no waking pulse yet (server keeps the old value)
                else -> summary?.hrAvg ?: 0
            }

            // Sleep summaries are keyed by wake-up date, so the sleep interval may
            // start on the previous calendar day. Use all decoded minute samples,
            // not only daySamples, to include the pre-midnight part of the night.
            // Prefer the sleep file's RR-derived pulse. Fall back to minute activity HR
            // only for old or partial sleep files without RR packets.
            val sleepPulse = sleep?.pulseAvgSleep?.takeIf { it > 0 } ?: if (sleep != null) {
                val sleepStart = sleep.bedTimeSeconds.toLong()
                val wakeTime = sleep.wakeupTimeSeconds.toLong()
                val historyHr = hrHistory.hr.entries
                    .asSequence()
                    .filter { it.key.toLong() >= sleepStart && it.key.toLong() < wakeTime }
                    .map { it.value }
                    .filter { it in 30..220 }
                    .toList()
                val currentHr = unique.asSequence()
                    .filter { it.timestampSeconds.toLong() >= sleepStart && it.timestampSeconds.toLong() < wakeTime }
                    .mapNotNull { it.heartRate?.takeIf { bpm -> bpm > 0 } }
                    .toList()
                when {
                    historyHr.size >= 3 -> historyHr.average().toInt()
                    currentHr.isNotEmpty() -> currentHr.average().toInt()
                    else -> 0
                }
            } else {
                0
            }

            VersionedLog.i(TAG, "📊 $date: pulse_day=$pulse BPM (waking samples=${awakeHr.size}/${dayHr.size}), pulse_sleep=$sleepPulse BPM")

            BandDayAggregate(
                date = date,
                stepsTotal = stepsTotal,
                pulseAvgDay = pulse,
                pulseAvgSleep = sleepPulse,
                // The Xiaomi type=16 summary duration may cover only detected sleep stages.
                // The file header's bedTime/wakeupTime represents the actual sleep session window.
                // Prefer that window and keep the summary duration only as a fallback for invalid timestamps.
                sleepHours = sleep?.let {
                    val bedWakeMinutes = if (it.wakeupTimeSeconds > it.bedTimeSeconds) {
                        (it.wakeupTimeSeconds.toLong() - it.bedTimeSeconds.toLong()) / 60L
                    } else {
                        0L
                    }
                    val durationMinutes = if (bedWakeMinutes > 0L) {
                        bedWakeMinutes
                    } else {
                        it.sleepDurationMinutes.toLong().coerceAtLeast(0L)
                    }
                    VersionedLog.i(
                        TAG,
                        "📊 $date: sleep duration source=" +
                            (if (bedWakeMinutes > 0L) "bed..wake" else "summary") +
                            ", bedWake=" + bedWakeMinutes + "m, summary=" +
                            it.sleepDurationMinutes + "m, used=" + durationMinutes + "m"
                    )
                    durationMinutes / 60.0
                } ?: 0.0,
                sleepAwakenings = sleep?.wakeCount ?: 0,
            )
        }
    }
    private val TAG = AppVersion.logTag("SyncHelper")
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
        VersionedLog.i(TAG, "═══ Starting sync: last $days days (from ${today.minusDays(days.toLong())} to $today)")
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
            VersionedLog.e(TAG, msg)
            onStatus(msg)
            return
        }

        try {
            VersionedLog.i(TAG, "Resolving active server (primary: $primaryUrl, backup: $backupUrl)")
            val (activeUrl, cookie) = resolveActiveServer(primaryUrl, backupUrl, pin, onStatus)
            VersionedLog.i(TAG, "✅ Connected to: $activeUrl")

            var current = fromDate
            var successCount = 0
            var errorCount = 0
            val totalDays = java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate) + 1

            while (!current.isAfter(toDate)) {
                val dayNum = successCount + errorCount + 1
                val progressMsg = "📅 Day $dayNum/$totalDays: syncing $current"
                VersionedLog.i(TAG, progressMsg)
                onStatus(progressMsg)
                try {
                    syncSingleDay(client, activeUrl, cookie, current)
                    successCount++
                    VersionedLog.i(TAG, "  ✅ $current synced successfully")
                } catch (e: Exception) {
                    VersionedLog.e(TAG, "  ❌ Error syncing $current: ${e.message}", e)
                    errorCount++
                    onStatus("❌ Error on $current: ${e.localizedMessage}")
                }
                current = current.plusDays(1)
            }

            val finishMsg = "═══ Sync finished: $successCount ok, $errorCount errors"
            VersionedLog.i(TAG, finishMsg)
            onStatus(finishMsg)
        } catch (e: Exception) {
            VersionedLog.e(TAG, "❌ Fatal error during sync: ${e.message}", e)
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
                VersionedLog.d(TAG, "Attempting server ${idx + 1}/${candidates.size}: $url")
                val cookie = login(url, pin)
                VersionedLog.d(TAG, "✅ Login successful to $url")
                return url to cookie
            } catch (e: Exception) {
                VersionedLog.w(TAG, "❌ Server $url failed: ${e.message}")
                onStatus("⚠️ $url недоступен, пробую следующий...")
                lastError = e
            }
        }

        val errMsg = "❌ All servers failed: ${lastError?.message}"
        VersionedLog.e(TAG, errMsg)
        throw Exception(errMsg)
    }

    private suspend fun login(url: String, pin: String): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val cookieStore = mutableListOf<Cookie>()
            val okClient = OkHttpClient.Builder()
                .followRedirects(true)
                .cookieJar(object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                        cookieStore.removeAll { it.name == "session_pin" && it.domain == url.host }
                        cookieStore.addAll(cookies)
                    }

                    override fun loadForRequest(url: HttpUrl): List<Cookie> =
                        cookieStore.filter { it.matches(url) }
                })
                .connectTimeout(FALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(FALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(FALLBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            val loginBody = FormBody.Builder().add("pin", pin).build()
            val loginReq = Request.Builder().url("$url/login").post(loginBody).build()
            val loginResp = okClient.newCall(loginReq).execute()
            val setCookieHeader = loginResp.header("Set-Cookie")
            val finalUrl = loginResp.request.url
            val bodyPreview = loginResp.peekBody(512).string().replace("\n", " ").replace("\r", " ")
            VersionedLog.d(TAG, "Login response: HTTP ${loginResp.code}, setCookie=${setCookieHeader != null}")
            val cookie = cookieStore.firstOrNull { it.name == "session_pin" }?.let {
                "session_pin=" + it.value
            } ?: throw Exception("Login failed: HTTP " + loginResp.code + ", finalUrl=" + finalUrl + ", session cookie missing")
            loginResp.close()
            cookie
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
        VersionedLog.d(TAG, "↓ Fetching Health Connect data for $targetDay")

        val startOfDay = targetDay.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant()
        val endOfDay = targetDay.plusDays(1).atStartOfDay().atZone(ZoneId.systemDefault()).toInstant()

        // 1. Steps
        VersionedLog.d(TAG, "  📊 Reading steps...")
        val stepsRequest = AggregateRequest(
            metrics = setOf(StepsRecord.COUNT_TOTAL),
            timeRangeFilter = TimeRangeFilter.between(startOfDay, endOfDay)
        )
        val stepsResp = client.aggregate(stepsRequest)
        val totalSteps = stepsResp[StepsRecord.COUNT_TOTAL] ?: 0L
        VersionedLog.d(TAG, "  ✓ Steps: $totalSteps")

        // 2. Sleep — read BEFORE heart rate, so we know the wake/sleep window
        // and can split heart rate into "waking hours" vs "sleep" averages
        // instead of one flat whole-calendar-day average (which is dragged
        // down by naturally-lower overnight readings and doesn't match what
        // Mi Fitness shows as "average pulse"). Also counts distinct
        // awakening episodes inside the same session.
        VersionedLog.d(TAG, "  📊 Reading sleep...")
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
                val bedWakeMinutes = ((longest.endTime.toEpochMilli() - longest.startTime.toEpochMilli()) / 60000L).toInt()
                sleepHours = bedWakeMinutes / 60.0
                VersionedLog.d(TAG, "  ✓ Sleep session: $sleepHours hours (${sleepRecords.size} sessions, wake time: $wakeTime)")

                sleepAwakenings = countSleepAwakenings(longest)
                VersionedLog.d(TAG, "  ✓ Sleep awakenings: $sleepAwakenings")
            }
        } else {
            VersionedLog.w(TAG, "  ⚠ Sleep: no data")
        }

        // 3. Heart rate — split into "day" (waking hours only) and "sleep" averages.
        VersionedLog.d(TAG, "  📊 Reading heart rate...")

        // Waking-hours window: from wake time to end of day. Falls back to the
        // whole calendar day if we couldn't determine a wake time (no sleep
        // session found) — better a slightly-off number than none at all.
        val wakingStart = wakeTime ?: startOfDay
        val hrDayAvg = avgHeartRate(client, wakingStart, endOfDay)
        if (hrDayAvg > 0) {
            VersionedLog.d(TAG, "  ✓ Heart rate (waking hours, $wakingStart .. $endOfDay): $hrDayAvg BPM")
        } else {
            VersionedLog.w(TAG, "  ⚠ Heart rate (waking hours): no data")
        }

        // Sleep window: only meaningful if we found a sleep session.
        val hrSleepAvg = if (sleepStart != null && wakeTime != null) {
            val v = avgHeartRate(client, sleepStart, wakeTime)
            if (v > 0) {
                VersionedLog.d(TAG, "  ✓ Heart rate (sleep, $sleepStart .. $wakeTime): $v BPM")
            } else {
                VersionedLog.w(TAG, "  ⚠ Heart rate (sleep window): no data")
            }
            v
        } else {
            VersionedLog.w(TAG, "  ⚠ Heart rate (sleep): skipped, no sleep session")
            0L
        }

        VersionedLog.i(TAG, "📤 Posting to server: steps=$totalSteps, hr_day=$hrDayAvg BPM, hr_sleep=$hrSleepAvg BPM, " +
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
    private data class ServerSyncResult(
        val date: String,
        val sleepHours: Double,
        val pulseAvgDay: Int,
        val pulseAvgSleep: Int,
        val stepsTotal: Int,
        val sleepAwakenings: Int,
    )

    private suspend fun postToServer(
        baseUrl: String,
        cookie: String,
        date: String,
        sleep: Double,
        hrDay: Int,
        hrSleep: Int,
        stepsTotal: Int,
        sleepAwakenings: Int
    ): ServerSyncResult {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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

                VersionedLog.d(TAG, "Sending POST to $baseUrl/sync for $date")
                val syncResp = client.newCall(syncReq).execute()

                if (syncResp.isSuccessful) {
                    val responseBody = syncResp.body?.string().orEmpty()
                    val json = JSONObject(responseBody)
                    VersionedLog.i(TAG, "✅ Server accepted data for $date (HTTP ${syncResp.code})")
                    return@withContext ServerSyncResult(
                        date = json.getString("date"),
                        sleepHours = json.getDouble("sleep_hours"),
                        pulseAvgDay = json.getInt("pulse_avg_day"),
                        pulseAvgSleep = json.getInt("pulse_avg_sleep"),
                        stepsTotal = json.getInt("steps_total"),
                        sleepAwakenings = json.getInt("sleep_awakenings"),
                    )
                } else {
                    throw Exception("Server returned HTTP ${syncResp.code}")
                }
            } catch (e: Exception) {
                VersionedLog.e(TAG, "❌ Failed to post to server: ${e.message}", e)
                throw Exception("Failed to post data to server for $date: ${e.message}")
            }
        }
    }
}
