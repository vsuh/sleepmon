package com.example.sleepmonitorsync

object AppVersion {
    /**
     * Single source of truth for the app version (UI, Logcat, APK versionCode/versionName).
     * build.gradle.kts parses the line `const val NUMBER = <int>` below, so keep that format
     * and bump the number HERE only. Never lower it: Android refuses to install an APK with
     * a smaller versionCode over an already installed one.
     */
    const val NUMBER = 96
    const val DATE = "09.10.2026"

    val label: String
        get() = "v$NUMBER ($DATE)"

    fun buildTag(change: String): String = "$label - $change"

    fun logTag(component: String): String = component
}
