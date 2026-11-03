package dev.alenajam.opendialer.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alenajam.opendialer.remote.RemoteDesk
import dev.alenajam.opendialer.remote.RemoteDeskService
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Pairs this phone with the TWIGA dashboard so the owner can dial, answer and monitor calls remotely. */
@Composable
fun RemoteDeskCard() {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var paired by remember { mutableStateOf(RemoteDesk.isPaired(context)) }
    var business by remember { mutableStateOf(RemoteDesk.business(context)) }
    var ai by remember { mutableStateOf(RemoteDesk.aiEnabled(context)) }
    var code by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val muted = Color(0xFF667085)

    fun startDesk() {
        runCatching { context.startForegroundService(Intent(context, RemoteDeskService::class.java)) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startDesk() }
    fun askPermissions() {
        val list = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(list.toTypedArray())
    }

    LaunchedEffect(paired) {
        if (paired) startDesk()
        while (paired) {
            ai = RemoteDesk.aiEnabled(context)
            delay(2000)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Remote desk", fontWeight = FontWeight.Bold)
            if (!paired) {
                Text("On the TWIGA dashboard open Dialer, tap Pair phone, then type the 6-digit code here.", fontSize = 12.sp, color = muted)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(6) },
                    label = { Text("Pairing code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = code.length == 6 && !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        busy = true
                        note = "Connecting..."
                        val entered = code
                        Thread {
                            var problem = ""
                            try {
                                val out = JSONObject(RemoteDesk.rpc("device_claim", JSONObject().put("p_code", entered).put("p_name", Build.MODEL ?: "Phone")))
                                if (out.has("error")) problem = out.optString("error")
                                else RemoteDesk.save(context, out.getString("token"), out.optString("business"))
                            } catch (e: Exception) {
                                problem = e.message ?: "Could not connect. Check the internet."
                            }
                            main.post {
                                busy = false
                                if (problem.isBlank()) {
                                    paired = true
                                    business = RemoteDesk.business(context)
                                    note = "Connected."
                                    askPermissions()
                                } else {
                                    note = problem
                                }
                            }
                        }.start()
                    }
                ) { Text("Connect to dashboard") }
            } else {
                Text("Connected to $business", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI answering")
                        Text("Synced with the dashboard. When on, incoming calls are tagged for the AI receptionist. Voice answering over a SIM line still needs an audio bridge.", fontSize = 11.sp, color = muted)
                    }
                    Switch(checked = ai, onCheckedChange = { v ->
                        ai = v
                        RemoteDesk.prefs(context).edit().putBoolean("ai", v).apply()
                        val token = RemoteDesk.token(context)
                        Thread { runCatching { RemoteDesk.rpc("device_set_ai", JSONObject().put("p_token", token).put("p_enabled", v)) } }.start()
                    })
                }
                OutlinedButton(onClick = { askPermissions() }, modifier = Modifier.fillMaxWidth()) { Text("Check phone permissions") }
                OutlinedButton(onClick = {
                    context.stopService(Intent(context, RemoteDeskService::class.java))
                    RemoteDesk.unpair(context)
                    paired = false; business = ""; note = "Disconnected."
                }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
            }
            if (note.isNotBlank()) Text(note, fontSize = 12.sp, color = muted)
        }
    }
}
