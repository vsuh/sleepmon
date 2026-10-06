package com.example.sleepmonitorsync

import com.example.sleepmonitorsync.VersionedLog

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.sleepmonitorsync.band.BandCredentials

class SyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val workId = id
        WorkManagerDiagnostics.recordStarted(applicationContext, workId)
        VersionedLog.i(TAG, "Starting Xiaomi band background sync (" + AppVersion.label + ")")

        try {

        val prefs = applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val primaryUrl = prefs.getString("serverUrl", "") ?: ""
        val backupUrl = prefs.getString("serverUrlBackup", "") ?: ""
        val pin = prefs.getString("appPin", "") ?: ""

        if (primaryUrl.isEmpty() || pin.isEmpty()) {
            VersionedLog.w(TAG, "Server URL or PIN is empty. Aborting.")
            val result = Result.failure()
            WorkManagerDiagnostics.recordFinished(
                applicationContext, workId, WorkManagerDiagnostics.Outcome.FAILURE, "Server URL or PIN is empty"
            )
            return result
        }

        val credentials = BandCredentials.load(applicationContext)
        val authKey = credentials.authKeyHex.trim().removePrefix("0x").removePrefix("0X")
        if (authKey.length != 32 || authKey.any { it.digitToIntOrNull(16) == null }) {
            VersionedLog.w(TAG, "Xiaomi auth key is not configured. Waiting for user to enter it in Settings.")
            val result = Result.failure()
            WorkManagerDiagnostics.recordFinished(
                applicationContext, workId, WorkManagerDiagnostics.Outcome.FAILURE, "Xiaomi auth key is not configured"
            )
            return result
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
            val result = Result.success()
            WorkManagerDiagnostics.recordFinished(
                applicationContext, workId, WorkManagerDiagnostics.Outcome.SUCCESS
            )
            result
        } else {
            // WorkManager will retry transient Bluetooth/network/server failures.
            val result = Result.retry()
            WorkManagerDiagnostics.recordFinished(
                applicationContext, workId, WorkManagerDiagnostics.Outcome.RETRY
            )
            result
        }
        } catch (t: Throwable) {
            WorkManagerDiagnostics.recordFinished(
                applicationContext,
                workId,
                WorkManagerDiagnostics.Outcome.EXCEPTION,
                t::class.java.simpleName + ": " + (t.message ?: "no message")
            )
            throw t
        }
    }

    override fun onStopped() {
        val stopReason = if (android.os.Build.VERSION.SDK_INT >= 31) getStopReason() else null
        WorkManagerDiagnostics.recordStopped(applicationContext, id, stopReason)
        super.onStopped()
    }

    companion object {
        private val TAG = AppVersion.logTag("SyncWorker")
    }
}
