package com.example.sleepmonitorsync

import com.example.sleepmonitorsync.VersionedLog

import android.Manifest
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.example.sleepmonitorsync.band.BandCredentials
import com.example.sleepmonitorsync.theme.SleepMonitorSyncTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        /**
         * Bump this on every code change and mention it when reporting test results,
         * so we always know which build a log/screenshot came from (per user request,
         * 2026-09-15 - see 10-projects/sleep-monitor/task.md "process rule" entry).
         * Format: "vN (ДД.ММ.ГГГГ) - краткое описание изменения".
         */
        val APP_BUILD_TAG = AppVersion.buildTag("direct SPP; UI cleanup")
        val LOG_TAG = AppVersion.logTag("SleepMonitor")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VersionedLog.i(LOG_TAG, "=== $APP_BUILD_TAG ===")

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        val defaultServerUrl = "http://192.168.2.2:8000"
        val defaultServerUrlBackup = "https://sm.vsuh.duckdns.org:912"
        val defaultAppPin = "1679"

        // Defaults are write-once: an existing user value must never be overwritten
        // when Activity is recreated or a new APK is installed over the existing app.
        prefs.edit().apply {
            if (!prefs.contains("serverUrl")) putString("serverUrl", defaultServerUrl)
            if (!prefs.contains("serverUrlBackup")) putString("serverUrlBackup", defaultServerUrlBackup)
            if (!prefs.contains("appPin")) putString("appPin", defaultAppPin)
        }.apply()

        // Background sync scheduling now happens once in SleepMonitorApp.onCreate()
        // (process start), not here - re-running enqueueUniquePeriodicWork(UPDATE) on
        // every Activity creation risked racing with WorkManager's own periodic
        // dispatch, causing duplicate concurrent SyncWorker runs.

        var status by mutableStateOf("")
        var serverUrl by mutableStateOf(prefs.getString("serverUrl", defaultServerUrl) ?: "")
        var serverUrlBackup by mutableStateOf(
            prefs.getString("serverUrlBackup", defaultServerUrlBackup) ?: ""
        )
        var appPin by mutableStateOf(prefs.getString("appPin", defaultAppPin) ?: "")

        val requestBluetoothPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            status = if (granted) {
                "Разрешение Bluetooth получено. Нажмите «Синхронизация» ещё раз."
            } else {
                "Без разрешения «Устройства поблизости» подключение к браслету невозможно."
            }
        }

        setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * 1.2f)) {
            SleepMonitorSyncTheme {
            val context = LocalContext.current
            var showSettings by remember { mutableStateOf(false) }
            var selectedTab by remember { mutableStateOf("sync") }
            var queue by remember { mutableStateOf<SyncQueue.Pending?>(null) }
            var history by remember { mutableStateOf(SyncHistory.Snapshot(null, emptyList())) }
            var workDiagnostics by remember { mutableStateOf<WorkManagerDiagnostics.Snapshot?>(null) }
            val queueDateFormatter = remember { DateTimeFormatter.ofPattern("dd.MM.yyyy") }

            suspend fun refreshQueue() {
                queue = SyncQueue.load(context)
            }

            suspend fun refreshHistory() {
                history = SyncHistory.load(context)
            }

            suspend fun refreshWorkDiagnostics() {
                workDiagnostics = WorkManagerDiagnostics.load(context)
            }

            LaunchedEffect(Unit) {
                while (isActive) {
                    refreshQueue()
                    refreshHistory()
                    refreshWorkDiagnostics()
                    delay(2000L)
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
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
            } else {
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
                    Text(
                        AppVersion.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = { showSettings = true }) {
                        Text("⚙ Настройки")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { selectedTab = "sync" },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "Синхронизация",
                            fontWeight = if (selectedTab == "sync") FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    TextButton(
                        onClick = { selectedTab = "work" },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            "WorkManager",
                            fontWeight = if (selectedTab == "work") FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                if (selectedTab == "sync") {
                    SyncHistoryTable(history, queue)

                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            } else {
                                CoroutineScope(Dispatchers.Main).launch {
                                    status = "Синхронизация с Xiaomi Band..."
                                    try {
                                        SyncHelper.performBandSync(this@MainActivity, serverUrl, serverUrlBackup, appPin) { newStatus ->
                                            status = newStatus
                                        }
                                    } finally {
                                        refreshQueue()
                                        refreshHistory()
                                    }
                                }
                            }
                        },
                    ) {
                        Text("Синхронизировать")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Считывает данные с Xiaomi Band и отправляет их на сервер. Очередь очищается только после успешной синхронизации и подтверждения браслету.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (status.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(status)
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    SyncQueueStatus(
                        pending = queue,
                        dateFormatter = queueDateFormatter,
                    )
                } else {
                    WorkManagerDiagnosticsSection(workDiagnostics)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Последняя успешная синхронизация: " + formatLastSuccessfulSync(history.lastSuccessfulSyncAt),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
            }
            }
            }
            }
        }
    }
}

@Composable
private fun WorkManagerDiagnosticsSection(snapshot: WorkManagerDiagnostics.Snapshot?) {
    val info = snapshot?.workInfo
    val lastRun = snapshot?.lastRun

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Фоновая задача WorkManager",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text("Состояние задачи: " + WorkManagerDiagnostics.stateLabel(info?.state))
        Text(
            "Следующий запуск: " +
                if (info?.state == androidx.work.WorkInfo.State.ENQUEUED) {
                    WorkManagerDiagnostics.formatTime(info.nextScheduleTimeMillis) +
                        " (" + WorkManagerDiagnostics.formatDelay(info.nextScheduleTimeMillis) + ")"
                } else {
                    "-"
                }
        )
        Text("Попытка: " + (info?.runAttemptCount ?: "-"))
        Text(
            "Остановка: " +
                if (info == null) "-" else WorkManagerDiagnostics.stopReasonLabel(
                    if (android.os.Build.VERSION.SDK_INT >= 31) info.stopReason else null
                )
        )

        Spacer(modifier = Modifier.height(6.dp))
        Text("Последний фактический запуск Worker:")
        if (lastRun?.startedAt == null) {
            Text("  ещё ни разу не запускался")
        } else {
            Text("  начало: " + WorkManagerDiagnostics.formatTime(lastRun.startedAt))
            Text("  результат: " + WorkManagerDiagnostics.outcomeLabel(lastRun.outcome))
            lastRun.finishedAt?.let {
                Text("  завершение: " + WorkManagerDiagnostics.formatTime(it))
            }
            lastRun.stopReason?.let {
                Text("  stop reason: " + WorkManagerDiagnostics.stopReasonLabel(it))
            }
            lastRun.detail?.takeIf { it.isNotBlank() }?.let {
                Text("  детали: " + it)
            }
        }
    }
}

@Composable
private fun SyncQueueStatus(
    pending: SyncQueue.Pending?,
    dateFormatter: DateTimeFormatter,
) {
    val days = pending?.days.orEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Очередь синхронизации: ${days.size}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )

        if (days.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            days.asReversed().forEach { day ->
                val displayDate = runCatching {
                    LocalDate.parse(day.date).format(dateFormatter)
                }.getOrDefault(day.date)
                Text(
                    displayDate,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${"%.1f".format(day.sleepHours)} | ${day.pulseAvgDay} | " +
                        "${day.pulseAvgSleep} | ${day.stepsTotal} | ${day.sleepAwakenings}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

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
            "Если ключ авторизации перестал работать, заново извлеките его через xiaomi-extractor и вставьте сюда - пересборка приложения не требуется.",
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


private val RUSSIAN_SHORT_MONTHS = listOf(
    "янв.", "фев.", "мар.", "апр.", "май", "июн.",
    "июл.", "авг.", "сен.", "окт.", "ноя.", "дек."
)

private fun formatLastSuccessfulSync(timestampMillis: Long?): String {
    if (timestampMillis == null) return "нет данных"
    val dateTime = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
    val stamp = "${dateTime.dayOfMonth} ${RUSSIAN_SHORT_MONTHS[dateTime.monthValue - 1]} ${"%02d".format(Locale.ROOT, dateTime.hour)}:${"%02d".format(Locale.ROOT, dateTime.minute)}"
    val ageMinutes = ((System.currentTimeMillis() - timestampMillis) / 60000L).coerceAtLeast(0L)
    val age = when {
        ageMinutes < 60L -> "${ageMinutes}м назад"
        ageMinutes < 1440L -> "${ageMinutes / 60L}ч ${ageMinutes % 60L}м назад"
        else -> "${ageMinutes / 1440L}д ${((ageMinutes % 1440L) / 60L)}ч назад"
    }
    return "$stamp ($age)"
}

private fun formatShortDate(date: LocalDate): String =
    "${date.dayOfMonth} ${RUSSIAN_SHORT_MONTHS[date.monthValue - 1]}"

private fun formatSleep(hours: Double): String {
    if (hours <= 0.0) return "-"
    val totalMinutes = (hours * 60.0).toInt()
    return "${totalMinutes / 60}ч ${totalMinutes % 60}м"
}

private fun formatSteps(steps: Int): String =
    if (steps > 0) String.format(Locale.ROOT, "%,d", steps).replace(",", " ") else "-"

private data class DisplayDay(
    val sleepHours: Double,
    val pulseAvgDay: Int,
    val pulseAvgSleep: Int,
    val stepsTotal: Int,
    val sleepAwakenings: Int,
)

@Composable
private fun SyncHistoryTable(history: SyncHistory.Snapshot, pending: SyncQueue.Pending?) {
    val byDate = history.days.associate {
        it.date to DisplayDay(it.sleepHours, it.pulseAvgDay, it.pulseAvgSleep, it.stepsTotal, it.sleepAwakenings)
    }.toMutableMap()
    pending?.days?.forEach { day ->
        byDate[day.date] = DisplayDay(
            day.sleepHours, day.pulseAvgDay, day.pulseAvgSleep, day.stepsTotal, day.sleepAwakenings
        )
    }
    val today = LocalDate.now()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Последние 7 дней",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            TableCell("Дата", 1.1f, true)
            TableCell("Сон", 1.0f, true, alignEnd = true)
            TableCell("Пульс", 0.9f, true, alignEnd = true)
            TableCell("Во сне", 0.9f, true, alignEnd = true)
            TableCell("Шаги", 1.2f, true, alignEnd = true)
            TableCell("Проб.", 0.8f, true, alignEnd = true)
        }

        (0L..6L).forEach { offset ->
            val date = today.minusDays(offset)
            val day = byDate[date.toString()]
            Row(modifier = Modifier.fillMaxWidth()) {
                TableCell(formatShortDate(date), 1.1f)
                TableCell(formatSleep(day?.sleepHours ?: 0.0), 1.0f, alignEnd = true)
                TableCell(day?.pulseAvgDay?.takeIf { it > 0 }?.toString() ?: "-", 0.9f, alignEnd = true)
                TableCell(day?.pulseAvgSleep?.takeIf { it > 0 }?.toString() ?: "-", 0.9f, alignEnd = true)
                TableCell(day?.stepsTotal?.takeIf { it > 0 }?.let(::formatSteps) ?: "-", 1.2f, alignEnd = true)
                TableCell(day?.sleepAwakenings?.takeIf { it > 0 }?.toString() ?: "-", 0.8f, alignEnd = true)
            }
        }
    }
}

@Composable
private fun RowScope.TableCell(
    text: String,
    weight: Float,
    header: Boolean = false,
    alignEnd: Boolean = false,
) {
    Text(
        text = text,
        modifier = Modifier
            .weight(weight)
            .padding(vertical = 5.dp, horizontal = 2.dp),
        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
        style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
    )
}
