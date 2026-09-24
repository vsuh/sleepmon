package com.example.sleepmonitorsync.band

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manual test entry points for Этап A (auth) and Этап B (activity fetch) - not wired
 * into the periodic sync pipeline yet. Called from debug buttons in MainActivity.kt.
 *
 * Uses [XiaomiBandClassicConnection] (Bluetooth Classic SPP) - the only transport this
 * Mi Band 9 Pro responds to; [XiaomiBandConnection] (BLE GATT) is kept in the project
 * only for reference (confirmed non-functional for this device, see task.md).
 */
object XiaomiBandTester {
    private const val TAG = "XiaomiBandTester"

    /** Connects over Bluetooth Classic SPP, runs the auth handshake, disconnects. Returns a short human-readable result. */
    suspend fun testAuthSpp(context: Context): String {
        val credentials = BandCredentials.load(context)
        Log.i(TAG, "═══ Testing Xiaomi band auth (SPP) against ${credentials.macAddress}")
        return XiaomiBandClassicConnection.withExclusiveSppOperation {
            val connection = XiaomiBandClassicConnection(context, credentials)
            try {
                val result = connection.authenticate()
            result.fold(
                onSuccess = {
                    Log.i(TAG, "✅ Auth succeeded")
                    "✅ Успешно авторизовались на браслете ${credentials.macAddress} (SPP)"
                },
                onFailure = { e ->
                    Log.e(TAG, "❌ Auth failed", e)
                    "❌ Ошибка: ${e.message}"
                },
            )
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Auth + fetch today's activity data (Этап B). Returns a detailed human-readable summary. */
    suspend fun testActivityFetch(context: Context): String {
        val credentials = BandCredentials.load(context)
        Log.i(TAG, "═══ Testing activity fetch against ${credentials.macAddress}")
        return XiaomiBandClassicConnection.withExclusiveSppOperation {
            val connection = XiaomiBandClassicConnection(context, credentials)
            try {
                val authResult = connection.authenticate()
            if (authResult.isFailure) {
                val e = authResult.exceptionOrNull()
                Log.e(TAG, "❌ Auth failed", e)
                return@withExclusiveSppOperation "❌ Auth: ${e?.message}"
            }
            Log.i(TAG, "✅ Auth ok, requesting activity data...")
            val fetchResult = connection.fetchActivityData()
            fetchResult.fold(
                onSuccess = { r -> formatFetchResult(r) },
                onFailure = { e ->
                    Log.e(TAG, "❌ Fetch failed", e)
                    "❌ Fetch: ${e.message}"
                },
            )
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun formatFetchResult(r: XiaomiBandClassicConnection.FetchResult): String {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val sb = StringBuilder()

        sb.append("Файлов: ${r.filesReceived} распознано")
        if (r.filesUnsupported > 0) sb.append(", ${r.filesUnsupported} без парсера")
        if (r.filesFailed > 0) sb.append(", ${r.filesFailed} ошибок (CRC/повреждены)")
        sb.append("\n\n")

        // Summarize what's actually useful, rather than dumping the tail of the list
        // (which is usually all-zero padding minutes for the rest of the current day).
        val withHr = r.perMinuteSamples.filter { (it.heartRate ?: 0) > 0 }
        val totalSteps = r.perMinuteSamples.sumOf { it.steps ?: 0 }
        sb.append("Минутных записей: ${r.perMinuteSamples.size} (из них с пульсом: ${withHr.size})\n")
        sb.append("Шагов суммарно: $totalSteps\n")

        if (withHr.isNotEmpty()) {
            val hrValues = withHr.mapNotNull { it.heartRate }
            sb.append("Пульс: мин ${hrValues.min()}, макс ${hrValues.max()}, среднее ${hrValues.average().toInt()}\n")
            // Samples from different files can arrive/concatenate out of chronological
            // order (fixed 2026-09-22 - was using first()/last() of the list, which
            // produced backwards-looking ranges like "14:55–14:45").
            val minTs = withHr.minOf { it.timestampSeconds }
            val maxTs = withHr.maxOf { it.timestampSeconds }
            sb.append("Период с данными пульса: ${timeFmt.format(Date(minTs * 1000L))}")
            sb.append("–${timeFmt.format(Date(maxTs * 1000L))}\n")
        }

        if (r.dailySummaries.isNotEmpty()) sb.append("\n")
        for (s in r.dailySummaries) {
            sb.append("Сводка за день: шагов ${s.steps}, пульс покоя ${s.hrResting}, ")
            sb.append("пульс ${s.hrMin}–${s.hrMax} (ср. ${s.hrAvg}), ккал ${s.calories}\n")
        }

        if (r.unsupportedFileDescriptions.isNotEmpty()) {
            sb.append("\nБез парсера:\n")
            r.unsupportedFileDescriptions.forEach { sb.append("  $it\n") }
        }

        return sb.toString().trimEnd()
    }
}
