package com.example.sleepmonitorsync

import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.ZoneId

object SyncHelper {

    suspend fun performSync(
        client: HealthConnectClient,
        url: String,
        pin: String,
        days: Int,
        onStatus: (String) -> Unit
    ) {
        try {
            val today = LocalDate.now()
            
            // First login to get cookie once
            var cookie = ""
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val okClient = OkHttpClient.Builder().followRedirects(false).build()
                val loginBody = FormBody.Builder().add("pin", pin).build()
                val loginReq = Request.Builder().url("$url/login").post(loginBody).build()
                val loginResp = okClient.newCall(loginReq).execute()
                cookie = loginResp.header("Set-Cookie") ?: ""
            }

            for (i in days downTo 0) {
                val targetDay = today.minusDays(i.toLong())
                val startOfDay = targetDay.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant()
                val endOfDay = targetDay.plusDays(1).atStartOfDay().atZone(ZoneId.systemDefault()).toInstant()

                onStatus("Syncing ${targetDay}...")

                // 1. Steps
                val stepsRequest = AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, endOfDay)
                )
                val stepsResp = client.aggregate(stepsRequest)
                val totalSteps = stepsResp[StepsRecord.COUNT_TOTAL] ?: 0L

                // 2. Heart Rate
                val hrRequest = AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG),
                    timeRangeFilter = TimeRangeFilter.between(startOfDay, endOfDay)
                )
                val hrResp = client.aggregate(hrRequest)
                val hrAvg = hrResp[HeartRateRecord.BPM_AVG] ?: 0L

                // 3. Sleep
                val sleepStart = targetDay.minusDays(1).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant()
                val sleepEnd = targetDay.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
                val sleepReq = ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(sleepStart, sleepEnd)
                )
                val sleepRecords = client.readRecords(sleepReq).records
                var sleepHours = 0.0
                if (sleepRecords.isNotEmpty()) {
                    val longest = sleepRecords.maxByOrNull { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() }
                    if (longest != null) {
                        sleepHours = (longest.endTime.toEpochMilli() - longest.startTime.toEpochMilli()) / 3600000.0
                    }
                }

                postToServer(url, cookie, targetDay.toString(), sleepHours, hrAvg.toInt(), totalSteps.toInt())
            }
            onStatus("Synced successfully!")
        } catch (e: Exception) {
            Log.e("SyncHelper", "Error", e)
            onStatus("Error: ${e.localizedMessage}")
        }
    }

    private suspend fun postToServer(baseUrl: String, cookie: String, date: String, sleep: Double, hr: Int, steps: Int) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val client = OkHttpClient()
            
            val saveBody = FormBody.Builder()
                .add("date", date)
                .add("sleep_hours", sleep.toString())
                .add("pulse_avg_day", hr.toString())
                .add("pulse_avg_sleep", "0")
                .add("steps_1", steps.toString())
                .add("steps_2", "0")
                .add("well_being", "5")
                .add("alco", "false")
                .add("notes", "Synced from Android Companion")
                .build()
                
            val saveReq = Request.Builder()
                .url("$baseUrl/save")
                .addHeader("Cookie", cookie)
                .post(saveBody)
                .build()
                
            val saveResp = client.newCall(saveReq).execute()
            if (!saveResp.isSuccessful) {
                throw Exception("Server returned ${saveResp.code} for date $date")
            }
        }
    }
}
