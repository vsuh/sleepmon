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
        val url = prefs.getString("serverUrl", "") ?: ""
        val pin = prefs.getString("appPin", "") ?: ""

        if (url.isEmpty() || pin.isEmpty()) {
            Log.w("SyncWorker", "URL or PIN is empty. Aborting.")
            return Result.failure()
        }

        val client = HealthConnectClient.getOrCreate(applicationContext)
        
        var isSuccess = true
        
        SyncHelper.performSync(client, url, pin, 3) { status ->
            Log.i("SyncWorker", status)
            if (status.startsWith("Error")) {
                isSuccess = false
            }
        }

        return if (isSuccess) Result.success() else Result.retry()
    }
}
