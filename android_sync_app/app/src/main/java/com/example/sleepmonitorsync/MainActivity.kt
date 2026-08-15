package com.example.sleepmonitorsync

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

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
        
        var status by mutableStateOf("Ready")
        var serverUrl by mutableStateOf(prefs.getString("serverUrl", "http://192.168.2.4:8000") ?: "")
        var appPin by mutableStateOf(prefs.getString("appPin", "1234") ?: "")

        val requestPermissions = registerForActivityResult(requestPermissionActivityContract) { granted ->
            if (granted.containsAll(permissions)) {
                status = "Permissions granted! Ready to sync."
            } else {
                status = "Permissions not fully granted."
            }
        }

        setContent {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Sleep Monitor Sync", modifier = Modifier.padding(bottom = 16.dp))
                
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { 
                        serverUrl = it
                        prefs.edit().putString("serverUrl", it).apply()
                    },
                    label = { Text("Server URL") },
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
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(onClick = {
                    val client = HealthConnectClient.getOrCreate(this@MainActivity)
                    CoroutineScope(Dispatchers.Main).launch {
                        val granted = client.permissionController.getGrantedPermissions()
                        if (granted.containsAll(permissions)) {
                            status = "Syncing..."
                            syncData(client, serverUrl, appPin) { newStatus ->
                                status = newStatus
                            }
                        } else {
                            requestPermissions.launch(permissions)
                        }
                    }
                }) {
                    Text("Sync Now")
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                Text(status)
            }
        }
    }

    private suspend fun syncData(client: HealthConnectClient, url: String, pin: String, onStatus: (String) -> Unit) {
        val today = LocalDate.now()
        val startOfMonth = today.withDayOfMonth(1)
        val daysSinceStart = java.time.temporal.ChronoUnit.DAYS.between(startOfMonth, today).toInt()
        
        SyncHelper.performSync(client, url, pin, daysSinceStart, onStatus)
        
        // Schedule periodic background sync
        val syncWorkRequest = androidx.work.PeriodicWorkRequestBuilder<SyncWorker>(
            12, java.util.concurrent.TimeUnit.HOURS
        ).build()
        androidx.work.WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "SleepMonitorSync",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            syncWorkRequest
        )
    }
}
