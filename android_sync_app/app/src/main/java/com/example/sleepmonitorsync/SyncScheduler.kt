package com.example.sleepmonitorsync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Centralizes scheduling of the hourly background sync (WorkManager periodic work).
 * Called on app start and after device boot so sync keeps running even when
 * the app UI is closed/killed.
 */
object SyncScheduler {
    private const val WORK_NAME = "SleepMonitorSync"

    fun schedule(context: Context) {
        val syncWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            1, TimeUnit.HOURS
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncWorkRequest
        )
    }
}
