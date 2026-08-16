package com.example.sleepmonitorsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-schedules the hourly background sync after the device reboots.
 * WorkManager persists periodic work across reboots on its own in most cases,
 * but re-enqueueing here (idempotent, KEEP policy) guards against edge cases
 * on OEM ROMs that clear WorkManager's internal DB on boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SyncScheduler.schedule(context.applicationContext)
        }
    }
}
