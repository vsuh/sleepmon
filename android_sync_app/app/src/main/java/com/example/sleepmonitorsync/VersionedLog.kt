package com.example.sleepmonitorsync

import android.util.Log

/**
 * Stable Logcat tags plus the application build version in every log message.
 *
 * Use a stable component tag so one filter works across all builds, while the
 * message itself identifies the exact app version used for the run.
 */
object VersionedLog {
    private fun prefix(message: String): String = "[${AppVersion.label}] $message"

    fun d(tag: String, message: String) = Log.d(tag, prefix(message))
    fun i(tag: String, message: String) = Log.i(tag, prefix(message))
    fun w(tag: String, message: String) = Log.w(tag, prefix(message))
    fun e(tag: String, message: String) = Log.e(tag, prefix(message))
    fun e(tag: String, message: String, throwable: Throwable?) = Log.e(tag, prefix(message), throwable)
}
