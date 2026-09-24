package com.example.sleepmonitorsync

import android.Manifest
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import com.example.sleepmonitorsync.band.BandCredentials
import com.example.sleepmonitorsync.band.XiaomiBandTester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Bump this on every code change and mention it when reporting test results,
         * so we always know which build a log/screenshot came from (per user request,
         * 2026-09-15 - see 10-projects/sleep-monitor/task.md "process rule" entry).
         * Format: "vN (ДД.ММ.ГГГГ) - краткое описание изменения".
         */
        const val APP_BUILD_TAG = "v16 (24.09.2026) - Xiaomi Band → backend"
    }

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    private val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        // Defaults below are only fallback VALUES for display in the UI state —
        // getString() does NOT persist them to SharedPreferences on its own.
        // SyncWorker reads these same keys directly from SharedPreferences (with an
        // empty-string fallback, so it can detect "not configured" and abort safely).
        // Without writing the defaults back here, a fresh install shows a fully
        // filled-in form but SyncWorker sees empty values and silently aborts every
        // hour until the user manually edits a field. Persist any missing default now.
        val defaultServerUrl = "http://192.168.2.2:8000"
        val defaultServerUrlBackup = "https://sm.vsuh.duckdns.org:912"
        val defaultAppPin = "1679"
        prefs.edit().apply {
            if (!prefs.contains("serverUrl")) putString("serverUrl", defaultServerUrl)
            if (!prefs.contains("serverUrlBackup")) putString("serverUrlBackup", defaultServerUrlBackup)
            if (!prefs.contains("appPin")) putString("appPin", defaultAppPin)
        }.apply()

        // Background sync scheduling now happens once in SleepMonitorApp.onCreate()
        // (process start), not here — re-running enqueueUniquePeriodicWork(UPDATE) on
        // every Activity creation risked racing with WorkManager's own periodic
        // dispatch, causing duplicate concurrent SyncWorker runs.

        var status by mutableStateOf("Ready")
        var serverUrl by mutableStateOf(prefs.getString("serverUrl", defaultServerUrl) ?: "")
        var serverUrlBackup by mutableStateOf(
            prefs.getString("serverUrlBackup", defaultServerUrlBackup) ?: ""
        )
        var appPin by mutableStateOf(prefs.getString("appPin", defaultAppPin) ?: "")

        val today = LocalDate.now()
        var rangeFrom by mutableStateOf(today.minusDays(7))
        var rangeTo by mutableStateOf(today)

        val requestPermissions = registerForActivityResult(requestPermissionActivityContract) { granted ->
            if (granted.containsAll(permissions)) {
                status = "Permissions granted! Ready to sync."
            } else {
                status = "Permissions not fully granted."
            }
        }

        // Этап A/B (Classic SPP auth + activity fetch) — see
        // 10-projects/sleep-monitor/task.md. Separate debug section below, not part of
        // the Health Connect flow above.
        var bleStatus by mutableStateOf("")
        var bleRunning by mutableStateOf(false)
        // Which transport to run once BLUETOOTH_CONNECT/SCAN are granted - set by
        // whichever debug button was tapped, consumed by requestBluetoothPermissions'
        // callback below.
        var pendingTransport: (suspend () -> String)? = null

        fun runBandTest(transport: suspend () -> String) {
            bleRunning = true
            bleStatus = "Подключаюсь к браслету..."
            CoroutineScope(Dispatchers.Main).launch {
                bleStatus = transport()
                bleRunning = false
            }
        }

        // Classic SPP (XiaomiBandClassicConnection) needs BLUETOOTH_CONNECT to open the
        // RFCOMM socket, and BLUETOOTH_SCAN purely because BluetoothAdapter.cancelDiscovery()
        // is classified as a scan op by the platform - request both together.
        val requestBluetoothPermissions = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grantedMap ->
            val connectGranted = grantedMap[Manifest.permission.BLUETOOTH_CONNECT] == true
            val transport = pendingTransport
            pendingTransport = null
            if (connectGranted && transport != null) {
                runBandTest(transport)
            } else {
                bleStatus = "❌ Нет разрешения BLUETOOTH_CONNECT"
            }
        }

        fun requestAndRunBandTest(transport: suspend () -> String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pendingTransport = transport
                requestBluetoothPermissions.launch(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                )
            } else {
                runBandTest(transport)
            }
        }

        suspend fun withHealthPermissions(action: suspend (HealthConnectClient) -> Unit) {
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(permissions)) {
                action(client)
            } else {
                requestPermissions.launch(permissions)
            }
        }

        setContent {
            val context = LocalContext.current
            var showSettings by remember { mutableStateOf(false) }

            fun showDatePicker(initial: LocalDate, onPicked: (LocalDate) -> Unit) {
                DatePickerDialog(
                    context,
                    { _, year, month, dayOfMonth ->
                        onPicked(LocalDate.of(year, month + 1, dayOfMonth))
                    },
                    initial.year, initial.monthValue - 1, initial.dayOfMonth
                ).show()
            }

            if (showSettings) {
                SettingsScreen(
                    serverUrl = serverUrl,
                    onServerUrlChange = { serverUrl = it; prefs.edit().putString("serverUrl", it).apply() },
                    serverUrlBackup = serverUrlBackup,
                    onServerUrlBackupChange = { serverUrlBackup = it; prefs.edit().putString("serverUrlBackup", it).apply() },
                    appPin = appPin,
                    onAppPinChange = { appPin = it; prefs.edit().putString("appPin", it).apply() },
                    onBack = { showSettings = false },
                )
                return@setContent
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sleep Monitor Sync")
                    TextButton(onClick = { showSettings = true }) {
                        Text("⚙ Настройки")
                    }
                }
                Text(
                    "Build: $APP_BUILD_TAG",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                Text("Фоновая синхронизация запускается автоматически каждый час, даже если приложение закрыто.")

                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = {
                    CoroutineScope(Dispatchers.Main).launch {
                        status = "Синхронизация с Xiaomi Band..."
                        SyncHelper.performBandSync(this@MainActivity, serverUrl, serverUrlBackup, appPin) { newStatus ->
                            status = newStatus
                        }
                    }
                }) {
                    Text("Sync Now")
                }

                Spacer(modifier = Modifier.height(24.dp))
                Text("Массовая синхронизация за диапазон дат")
                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        showDatePicker(rangeFrom) { picked -> rangeFrom = picked }
                    }) {
                        Text("От: $rangeFrom")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        showDatePicker(rangeTo) { picked -> rangeTo = picked }
                    }) {
                        Text("До: $rangeTo")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(onClick = {
                    CoroutineScope(Dispatchers.Main).launch {
                        status = "Syncing range..."
                        withHealthPermissions { client ->
                            SyncHelper.performSyncRange(client, serverUrl, serverUrlBackup, appPin, rangeFrom, rangeTo) { newStatus ->
                                status = newStatus
                            }
                        }
                    }
                }) {
                    Text("Sync Range")
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(status)

                // --- Xiaomi Band Classic SPP: auth (Этап A) + activity fetch (Этап B), debug ---
                // BLE GATT button removed - confirmed non-functional for this device
                // (see task.md). Classic SPP is the only transport this band responds to.
                Spacer(modifier = Modifier.height(32.dp))
                Text("Xiaomi Band (debug)")
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Button(
                        enabled = !bleRunning,
                        onClick = {
                            requestAndRunBandTest { XiaomiBandTester.testAuthSpp(context) }
                        }
                    ) {
                        Text(if (bleRunning) "..." else "Проверить подключение")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = !bleRunning,
                        onClick = {
                            requestAndRunBandTest { XiaomiBandTester.testActivityFetch(context) }
                        }
                    ) {
                        Text(if (bleRunning) "..." else "Загрузить данные")
                    }
                }
                if (bleStatus.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(bleStatus)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Xiaomi Band result", bleStatus))
                    }) {
                        Text("Копировать результат")
                    }
                }
                // Bottom breathing room so the last line of a long fetch-result
                // message isn't flush against the screen edge / nav bar.
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/**
 * Settings screen: server/PIN config (moved here from the main screen, 2026-09-19 per
 * user request) plus the Xiaomi band's MAC address + auth key ([BandCredentials]) - the
 * auth key needs occasional manual updates whenever it rotates (e.g. after a Mi Fitness
 * reinstall - see 10-projects/sleep-monitor/task.md), so exposing it here avoids
 * needing a rebuild each time.
 */
@Composable
private fun SettingsScreen(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    serverUrlBackup: String,
    onServerUrlBackupChange: (String) -> Unit,
    appPin: String,
    onAppPinChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val initialCredentials = remember { BandCredentials.load(context) }
    var bandMac by remember { mutableStateOf(initialCredentials.macAddress) }
    var bandAuthKey by remember { mutableStateOf(initialCredentials.authKeyHex) }
    var saveStatus by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Настройки")
            TextButton(onClick = onBack) {
                Text("← Назад")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("Сервер синхронизации", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text("Server URL (основной)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = serverUrlBackup,
            onValueChange = onServerUrlBackupChange,
            label = { Text("Server URL (резервный)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = appPin,
            onValueChange = onAppPinChange,
            label = { Text("App PIN") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))
        Text("Xiaomi Band", style = MaterialTheme.typography.labelLarge)
        Text(
            "Ключ авторизации периодически протухает (обычно после переустановки/перепривязки Mi Fitness) - " +
                "переизвлеките его через xiaomi-extractor и вставьте сюда, без пересборки приложения.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = bandMac,
            onValueChange = { bandMac = it },
            label = { Text("MAC-адрес") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = bandAuthKey,
            onValueChange = { bandAuthKey = it },
            label = { Text("Ключ авторизации (32 hex-символа)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            val clean = bandAuthKey.trim().removePrefix("0x").removePrefix("0X")
            if (clean.length != 32 || clean.any { it.digitToIntOrNull(16) == null }) {
                saveStatus = "❌ Ключ должен быть ровно 32 hex-символа"
            } else {
                BandCredentials.save(context, BandCredentials(macAddress = bandMac.trim(), authKeyHex = clean))
                saveStatus = "✅ Сохранено"
            }
        }) {
            Text("Сохранить ключ браслета")
        }
        if (saveStatus.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(saveStatus)
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
