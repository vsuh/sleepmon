package com.example.sleepmonitorsync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.sleepmonitorsync.band.BandCredentials

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.i("SyncWorker", "Starting Xiaomi band background sync")

        val prefs = applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val primaryUrl = prefs.getString("serverUrl", "") ?: ""
        val backupUrl = prefs.getString("serverUrlBackup", "") ?: ""
        val pin = prefs.getString("appPin", "") ?: ""

        if (primaryUrl.isEmpty() || pin.isEmpty()) {
            Log.w("SyncWorker", "Server URL or PIN is empty. Aborting.")
            return Result.failure()
        }

        val credentials = BandCredentials.load(applicationContext)
        val authKey = credentials.authKeyHex.trim().removePrefix("0x").removePrefix("0X")
        if (authKey.length != 32 || authKey.any { it.digitToIntOrNull(16) == null }) {
            Log.w("SyncWorker", "Xiaomi auth key is not configured. Waiting for user to enter it in Settings.")
            return Result.failure()
        }

        val success = SyncHelper.performBandSync(
            applicationContext,
            primaryUrl,
            backupUrl,
            pin
        ) { status ->
            Log.i("SyncWorker", status)
        }

        return if (success) {
            Result.success()
        } else {
            // WorkManager will retry transient Bluetooth/network/server failures.
            Result.retry()
        }
    }
}
