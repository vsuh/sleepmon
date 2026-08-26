package com.example.sleepmonitorsync

import android.app.Application

/**
 * Application-level entry point.
 *
 * SyncScheduler.schedule() is called here (once per process lifetime) rather than
 * from MainActivity.onCreate() (which re-runs on every Activity creation, including
 * every time the user reopens the app while the process is still alive). Calling
 * enqueueUniquePeriodicWork(..., UPDATE, ...) that often risks racing with WorkManager
 * right at the moment it's about to dispatch the existing periodic job — observed as
 * two concurrent SyncWorker.doWork() executions with one immediately cancelled.
 * Application.onCreate() runs once when the process starts, which avoids that race
 * in all but the rare case where the process itself is created at the exact moment
 * a periodic run is due (already unavoidable without deeper WorkManager changes).
 */
class SleepMonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncScheduler.schedule(applicationContext)
    }
}
