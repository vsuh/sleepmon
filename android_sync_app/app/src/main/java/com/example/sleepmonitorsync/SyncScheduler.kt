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
 * NOTE: Minimum interval for WorkManager periodic work is 15 minutes on some devices,
 * but we request 5 minutes as a hint. Android will use the closest interval it supports.
 */
object SyncScheduler {
    private const val WORK_NAME = "SleepMonitorSync"
    private const val SYNC_INTERVAL_MINUTES = 5

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
