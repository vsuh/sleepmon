package com.example.sleepmonitorsync

object AppVersion {
    /** Single source of truth for the Android app version shown in UI and Logcat. */
    const val NUMBER = 65
    const val DATE = "30.09.2026"

    val label: String
        get() = "v$NUMBER ($DATE)"

    fun buildTag(change: String): String = "$label - $change"

    fun logTag(component: String): String = component
}
