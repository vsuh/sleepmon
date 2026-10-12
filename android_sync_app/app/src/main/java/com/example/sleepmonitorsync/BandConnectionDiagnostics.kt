package com.example.sleepmonitorsync

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Optional, removable diagnostics for overnight band disconnects.
 * Stores a bounded event ring in private app preferences. It never records the band MAC,
 * auth key, PIN, server URLs, payloads, or raw Bluetooth data.
 */
object BandConnectionDiagnostics {
    private const val PREFS = "band_connection_diagnostics"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 300
    private val lock = Any()
    @Volatile private var receiverRegistered = false
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val action = intent.action ?: return) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    record(context, "bluetooth_adapter_state", adapterStateLabel(state))
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> record(context, "bluetooth_acl_connected")
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> record(context, "bluetooth_acl_disconnected")
                BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED -> record(context, "bluetooth_acl_disconnect_requested")
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED ->
                    record(context, "dnd_filter_changed", interruptionFilterLabel(context))
            }
        }
    }

    /** Call once from Application.onCreate(). Events are collected while the app process is alive. */
    fun initialize(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (receiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            }
            runCatching {
                ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                receiverRegistered = true
                record(app, "diagnostics_started", "process_start")
                record(app, "initial_state", "dnd=" + interruptionFilterLabel(app))
            }.onFailure {
                record(app, "receiver_registration_failed", it.javaClass.simpleName)
            }
        }
    }

    fun record(context: Context, event: String, detail: String? = null) {
        val app = context.applicationContext
        val safeDetail = detail.orEmpty().replace(Regex("[0-9a-fA-F]{32,}"), "[redacted]").take(160)
        val bt = runCatching {
            val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            when (manager?.adapter?.state) {
                BluetoothAdapter.STATE_ON -> "ON"
                BluetoothAdapter.STATE_OFF -> "OFF"
                BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
                BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
                else -> "UNKNOWN"
            }
        }.getOrDefault("UNKNOWN")
        val dnd = interruptionFilterLabel(app)
        synchronized(lock) {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = runCatching { JSONArray(prefs.getString(KEY_EVENTS, "[]") ?: "[]") }.getOrDefault(JSONArray())
            val next = JSONArray()
            for (i in (old.length() - MAX_EVENTS + 1).coerceAtLeast(0) until old.length()) {
                next.put(old.optJSONObject(i))
            }
            next.put(JSONObject()
                .put("at", System.currentTimeMillis())
                .put("event", event.take(80))
                .put("detail", safeDetail)
                .put("bt", bt)
                .put("dnd", dnd))
            prefs.edit().putString(KEY_EVENTS, next.toString()).commit()
        }
    }

    fun read(context: Context): List<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val events = runCatching { JSONArray(prefs.getString(KEY_EVENTS, "[]") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until events.length()).mapNotNull { i ->
            events.optJSONObject(i)?.let { item ->
                val at = runCatching {
                    Instant.ofEpochMilli(item.optLong("at")).atZone(ZoneId.systemDefault()).format(timeFormat)
                }.getOrDefault("?")
                buildString {
                    append(at).append("  ").append(item.optString("event"))
                    item.optString("detail").takeIf { it.isNotBlank() }?.let { append(" | ").append(it) }
                    append(" | BT=").append(item.optString("bt", "?"))
                    append(" | DND=").append(item.optString("dnd", "?"))
                }
            }
        }
    }

    fun exportText(context: Context): String = read(context).joinToString(separator = "\n").ifBlank { "Журнал пока пуст." }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_EVENTS).commit()
        record(context, "diagnostics_cleared")
    }

    private fun interruptionFilterLabel(context: Context): String = runCatching {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        when (manager?.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS"
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> "UNKNOWN"
            else -> "UNKNOWN"
        }
    }.getOrDefault("UNKNOWN")

    private fun adapterStateLabel(state: Int): String = when (state) {
        BluetoothAdapter.STATE_ON -> "ON"
        BluetoothAdapter.STATE_OFF -> "OFF"
        BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
        BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
        else -> "UNKNOWN($state)"
    }
}
