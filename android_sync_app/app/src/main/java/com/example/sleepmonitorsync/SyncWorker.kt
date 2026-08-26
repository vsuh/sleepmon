package com.example.sleepmonitorsync

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i("SyncWorker", "Starting background sync")

        val prefs = applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val primaryUrl = prefs.getString("serverUrl", "") ?: ""
        val backupUrl = prefs.getString("serverUrlBackup", "") ?: ""
        val pin = prefs.getString("appPin", "") ?: ""

        if (primaryUrl.isEmpty() || pin.isEmpty()) {
            Log.w("SyncWorker", "URL or PIN is empty. Aborting.")
            return Result.failure()
        }

        val client = HealthConnectClient.getOrCreate(applicationContext)

        var isSuccess = true

        // Sync today + yesterday every hour: cheap, and catches sleep sessions
        // that only settle in Health Connect after waking up.
        // SyncHelper logs each status under the "SyncHelper" tag itself, so we
        // only need to check the outcome here.
        SyncHelper.performSync(client, primaryUrl, backupUrl, pin, 1) { status ->
            if (status.startsWith("Error")) {
                isSuccess = false
            }
        }

        return if (isSuccess) Result.success() else Result.retry()
    }
}
