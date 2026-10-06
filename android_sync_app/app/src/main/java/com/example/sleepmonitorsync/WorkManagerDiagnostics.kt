package com.example.sleepmonitorsync

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Persistent diagnostics for the hourly WorkManager worker.
 *
 * The persistent record is deliberately written before doWork() starts the real sync.
 * If the process is killed/crashes before a terminal record is written, the next app
 * launch can see STARTED without a completion and distinguish "Worker was never run"
 * from "Worker started but did not finish".
 */
object WorkManagerDiagnostics {
    const val WORK_NAME = "SleepMonitorSync"

    private const val PREFS = "work_manager_diagnostics"
    private const val KEY_WORK_ID = "last_work_id"
    private const val KEY_STARTED_AT = "last_started_at"
    private const val KEY_FINISHED_AT = "last_finished_at"
    private const val KEY_OUTCOME = "last_outcome"
    private const val KEY_DETAIL = "last_detail"
    private const val KEY_STOP_REASON = "last_stop_reason"

    enum class Outcome {
        STARTED, SUCCESS, RETRY, FAILURE, EXCEPTION, STOPPED,
    }

    data class LastRun(
        val workId: String?,
        val startedAt: Long?,
        val finishedAt: Long?,
        val outcome: Outcome?,
        val detail: String?,
        val stopReason: Int?,
    )

    data class Snapshot(
        val workInfo: WorkInfo?,
        val lastRun: LastRun,
    )

    fun recordStarted(context: Context, workId: UUID) {
        prefs(context).edit()
            .putString(KEY_WORK_ID, workId.toString())
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .remove(KEY_FINISHED_AT)
            .putString(KEY_OUTCOME, Outcome.STARTED.name)
            .remove(KEY_DETAIL)
            .remove(KEY_STOP_REASON)
            .apply()
    }

    fun recordFinished(
        context: Context,
        workId: UUID,
        outcome: Outcome,
        detail: String? = null,
    ) {
        val currentId = prefs(context).getString(KEY_WORK_ID, null)
        if (currentId != workId.toString()) return

        prefs(context).edit()
            .putLong(KEY_FINISHED_AT, System.currentTimeMillis())
            .putString(KEY_OUTCOME, outcome.name)
            .putStringOrRemove(KEY_DETAIL, detail)
            .remove(KEY_STOP_REASON)
            .apply()
    }

    fun recordStopped(context: Context, workId: UUID, stopReason: Int?) {
        val current = prefs(context)
        if (current.getString(KEY_WORK_ID, null) != workId.toString()) return
        if (current.getString(KEY_OUTCOME, null) != Outcome.STARTED.name) return

        current.edit()
            .putLong(KEY_FINISHED_AT, System.currentTimeMillis())
            .putString(KEY_OUTCOME, Outcome.STOPPED.name)
            .putStringOrRemove(KEY_DETAIL, "WorkManager: " + stopReasonLabel(stopReason))
            .apply {
                if (stopReason != null) putInt(KEY_STOP_REASON, stopReason)
            }
            .apply()
    }

    suspend fun load(context: Context): Snapshot = withContext(Dispatchers.IO) {
        val workInfo = runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME)
                .get()
                .firstOrNull()
        }.getOrNull()

        Snapshot(workInfo, loadLastRun(context))
    }

    fun formatTime(timestampMillis: Long?): String {
        if (timestampMillis == null) return "—"
        return runCatching {
            val dateTime = Instant.ofEpochMilli(timestampMillis)
                .atZone(ZoneId.systemDefault())
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.ROOT)
                .format(dateTime)
        }.getOrDefault("—")
    }

    fun formatDelay(timestampMillis: Long): String {
        val minutes = ((timestampMillis - System.currentTimeMillis()) / 60000L)
        if (minutes <= 0L) return "время уже наступило"
        return when {
            minutes < 60L -> "через ${minutes}м"
            minutes < 1440L -> "через ${minutes / 60L}ч ${minutes % 60L}м"
            else -> "через ${minutes / 1440L}д ${((minutes % 1440L) / 60L)}ч"
        }
    }

    fun outcomeLabel(outcome: Outcome?): String = when (outcome) {
        null -> "нет данных"
        Outcome.STARTED -> "запущен, завершение не зафиксировано"
        Outcome.SUCCESS -> "успешно завершён"
        Outcome.RETRY -> "завершён с retry"
        Outcome.FAILURE -> "завершён с failure"
        Outcome.EXCEPTION -> "завершён исключением"
        Outcome.STOPPED -> "остановлен WorkManager"
    }

    fun stopReasonLabel(reason: Int?): String {
        if (reason == null) return "нет данных"
        return when (reason) {
            WorkInfo.STOP_REASON_NOT_STOPPED -> "нет — задача не остановлена"
            WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "отменён приложением"
            WorkInfo.STOP_REASON_PREEMPT -> "вытеснен другим заданием"
            WorkInfo.STOP_REASON_TIMEOUT -> "таймаут"
            WorkInfo.STOP_REASON_DEVICE_STATE -> "состояние устройства"
            WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "ограничение: батарея"
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "ограничение: зарядка"
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "ограничение: сеть"
            WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "ограничение: idle"
            WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "ограничение: хранилище"
            WorkInfo.STOP_REASON_QUOTA -> "квота WorkManager"
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "ограничение фоновой работы"
            WorkInfo.STOP_REASON_APP_STANDBY -> "app standby"
            WorkInfo.STOP_REASON_USER -> "остановлено пользователем"
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "системная обработка"
            WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "изменилось расчётное время запуска"
            WorkInfo.STOP_REASON_UNKNOWN -> "неизвестно / возможен аварийный процесс"
            else -> "код " + reason
        }
    }

    fun stateLabel(state: WorkInfo.State?): String = when (state) {
        null -> "нет WorkInfo"
        WorkInfo.State.ENQUEUED -> "ENQUEUED"
        WorkInfo.State.RUNNING -> "RUNNING"
        WorkInfo.State.SUCCEEDED -> "SUCCEEDED"
        WorkInfo.State.FAILED -> "FAILED"
        WorkInfo.State.BLOCKED -> "BLOCKED"
        WorkInfo.State.CANCELLED -> "CANCELLED"
    }

    private fun loadLastRun(context: Context): LastRun {
        val p = prefs(context)
        val outcome = p.getString(KEY_OUTCOME, null)
            ?.let { value -> runCatching { Outcome.valueOf(value) }.getOrNull() }

        return LastRun(
            workId = p.getString(KEY_WORK_ID, null),
            startedAt = p.getLong(KEY_STARTED_AT, 0L).takeIf { it > 0L },
            finishedAt = p.getLong(KEY_FINISHED_AT, 0L).takeIf { it > 0L },
            outcome = outcome,
            detail = p.getString(KEY_DETAIL, null),
            stopReason = if (p.contains(KEY_STOP_REASON)) {
                p.getInt(KEY_STOP_REASON, WorkInfo.STOP_REASON_UNKNOWN)
            } else {
                null
            },
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun android.content.SharedPreferences.Editor.putStringOrRemove(
        key: String,
        value: String?,
    ): android.content.SharedPreferences.Editor {
        return if (value == null) remove(key) else putString(key, value)
    }
}
