package com.example.sleepmonitorsync

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    private val requestPermissionActivityContract = PermissionController.createRequestPermissionResultContract()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)

        // Schedule hourly background sync unconditionally on every app start,
        // so it keeps running even if the app is closed/killed afterwards.
        // ExistingPeriodicWorkPolicy.KEEP makes this a no-op if already scheduled.
        SyncScheduler.schedule(applicationContext)

        var status by mutableStateOf("Ready")
        var serverUrl by mutableStateOf(prefs.getString("serverUrl", "http://192.168.2.4:8000") ?: "")
        var serverUrlBackup by mutableStateOf(
            prefs.getString("serverUrlBackup", "https://sm.vsuh.duckdns.org:9124") ?: ""
        )
        var appPin by mutableStateOf(prefs.getString("appPin", "1234") ?: "")

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

            fun showDatePicker(initial: LocalDate, onPicked: (LocalDate) -> Unit) {
                DatePickerDialog(
                    context,
                    { _, year, month, dayOfMonth ->
                        onPicked(LocalDate.of(year, month + 1, dayOfMonth))
                    },
                    initial.year, initial.monthValue - 1, initial.dayOfMonth
                ).show()
            }

            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Sleep Monitor Sync", modifier = Modifier.padding(bottom = 16.dp))

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        prefs.edit().putString("serverUrl", it).apply()
                    },
                    label = { Text("Server URL (основной)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = serverUrlBackup,
                    onValueChange = {
                        serverUrlBackup = it
                        prefs.edit().putString("serverUrlBackup", it).apply()
                    },
                    label = { Text("Server URL (резервный, если основной недоступен 5 сек)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = appPin,
                    onValueChange = {
                        appPin = it
                        prefs.edit().putString("appPin", it).apply()
                    },
                    label = { Text("App PIN") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text("Фоновая синхронизация запускается автоматически каждый час, даже если приложение закрыто.")

                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = {
                    CoroutineScope(Dispatchers.Main).launch {
                        status = "Syncing..."
                        withHealthPermissions { client ->
                            val startOfMonth = today.withDayOfMonth(1)
                            val daysSinceStart = java.time.temporal.ChronoUnit.DAYS.between(startOfMonth, today).toInt()
                            SyncHelper.performSync(client, serverUrl, serverUrlBackup, appPin, daysSinceStart) { newStatus ->
                                status = newStatus
                            }
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
            }
        }
    }
}
