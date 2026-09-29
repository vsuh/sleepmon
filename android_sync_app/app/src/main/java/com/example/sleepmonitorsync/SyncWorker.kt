import com.example.sleepmonitorsync.VersionedLog
package com.example.sleepmonitorsync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.sleepmonitorsync.band.BandCredentials

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        VersionedLog.i(TAG, "Starting Xiaomi band background sync (${AppVersion.label})")

        val prefs = applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val primaryUrl = prefs.getString("serverUrl", "") ?: ""
        val backupUrl = prefs.getString("serverUrlBackup", "") ?: ""
        val pin = prefs.getString("appPin", "") ?: ""

        if (primaryUrl.isEmpty() || pin.isEmpty()) {
            VersionedLog.w(TAG, "Server URL or PIN is empty. Aborting.")
            return Result.failure()
        }

        val credentials = BandCredentials.load(applicationContext)
        val authKey = credentials.authKeyHex.trim().removePrefix("0x").removePrefix("0X")
        if (authKey.length != 32 || authKey.any { it.digitToIntOrNull(16) == null }) {
            VersionedLog.w(TAG, "Xiaomi auth key is not configured. Waiting for user to enter it in Settings.")
            return Result.failure()
        }

        val success = SyncHelper.performBandSync(
            applicationContext,
            primaryUrl,
            backupUrl,
            pin
        ) { status ->
            VersionedLog.i(TAG, status)
        }

        return if (success) {
            Result.success()
        } else {
            // WorkManager will retry transient Bluetooth/network/server failures.
            Result.retry()
        }
    }

    companion object {
        private val TAG = AppVersion.logTag("SyncWorker")
    }
}
