package com.example.sleepmonitorsync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Optional UI for BandConnectionDiagnostics; removable together with the diagnostics module. */
@Composable
fun BandDiagnosticsPanel() {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(BandConnectionDiagnostics.read(context)) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Журнал Bluetooth / ночных отключений", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("До 300 событий, хранится только на телефоне. События собираются, пока процесс приложения жив.", fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { entries = BandConnectionDiagnostics.read(context) }) { Text("Обновить") }
            Button(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Sleep Monitor diagnostics", BandConnectionDiagnostics.exportText(context)))
                Toast.makeText(context, "Журнал скопирован", Toast.LENGTH_SHORT).show()
                entries = BandConnectionDiagnostics.read(context)
            }) { Text("Копировать") }
            OutlinedButton(onClick = {
                BandConnectionDiagnostics.clear(context)
                entries = BandConnectionDiagnostics.read(context)
            }) { Text("Очистить") }
        }
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Text("Событий пока нет.")
        } else {
            Column(modifier = Modifier.fillMaxWidth().height(260.dp).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                entries.asReversed().forEach { line ->
                    Text(line, fontSize = 11.sp, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
        }
    }
}
