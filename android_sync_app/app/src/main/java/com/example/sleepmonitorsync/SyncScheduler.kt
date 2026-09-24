package com.example.sleepmonitorsync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Centralizes scheduling of the background sync (WorkManager periodic work).
 * Called on app start and after device boot so sync keeps running even when
 * the app UI is closed/killed.
 *
 * WorkManager has a platform minimum for periodic work; we request a 60-minute
 * interval, matching the user-facing text in MainActivity.
 */
object SyncScheduler {
    private const val WORK_NAME = "SleepMonitorSync"
    private const val SYNC_INTERVAL_MINUTES = 60

    fun schedule(context: Context) {
        val syncWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            SYNC_INTERVAL_MINUTES.toLong(), TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncWorkRequest
        )
    }
}
