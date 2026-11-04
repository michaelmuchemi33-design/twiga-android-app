package dev.alenajam.opendialer.ui

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alenajam.opendialer.remote.RemoteDesk
import dev.alenajam.opendialer.update.UpdateManager
import org.json.JSONObject

private val MutedText = Color(0xFF667085)

/** One big description of the business. The AI receptionist and the dashboard both use it. */
@Composable
fun AiSettingsCard() {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val paired = RemoteDesk.isPaired(context)
    var prompt by remember { mutableStateOf("") }
    var greeting by remember { mutableStateOf("") }
    var autoReply by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(paired) {
        if (paired) {
            val token = RemoteDesk.token(context)
            Thread {
                runCatching { JSONObject(RemoteDesk.rpc("device_get_ai", JSONObject().put("p_token", token))) }
                    .onSuccess { o ->
                        main.post {
                            prompt = o.optString("prompt")
                            greeting = o.optString("greeting")
                            autoReply = o.optString("auto_reply")
                        }
                    }
            }.start()
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("My business and AI", fontWeight = FontWeight.Bold)
            if (!paired) {
                Text("Connect this phone to the dashboard first (Remote desk card), then describe your business here.", fontSize = 12.sp, color = MutedText)
            } else {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(20000) },
                    label = { Text("What we offer: services, prices, hours, location, rules") },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("${prompt.length} / 20000", fontSize = 11.sp, color = MutedText)
                OutlinedTextField(
                    value = greeting,
                    onValueChange = { greeting = it.take(500) },
                    label = { Text("Greeting") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = autoReply,
                    onValueChange = { autoReply = it.take(500) },
                    label = { Text("Message for missed calls") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        note = "Saving..."
                        val token = RemoteDesk.token(context)
                        val p = prompt
                        val g = greeting
                        val a = autoReply
                        Thread {
                            val ok = runCatching {
                                RemoteDesk.rpc(
                                    "device_save_ai",
                                    JSONObject().put("p_token", token).put("p_prompt", p).put("p_greeting", g).put("p_auto_reply", a)
                                )
                            }
                            main.post { note = if (ok.isSuccess) "Saved. The dashboard has it too." else "Could not save. Check the internet." }
                        }.start()
                    }
                ) { Text("Save") }
                if (note.isNotBlank()) Text(note, fontSize = 12.sp, color = MutedText)
            }
        }
    }
}

@Composable
fun WhatsAppSoonCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WhatsApp manager", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("COMING SOON", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF47721))
            }
            Text("Reply to customers, share your price list and take orders on WhatsApp, all from here.", fontSize = 12.sp, color = MutedText)
        }
    }
}

@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var info by remember { mutableStateOf<UpdateManager.Info?>(null) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(-1) }
    var auto by remember { mutableStateOf(UpdateManager.autoEnabled(context)) }

    fun check() {
        status = "Checking..."
        Thread {
            val r = runCatching { UpdateManager.check(context) }
            main.post {
                r.onSuccess {
                    info = it
                    status = if (it == null) "TWIGA is up to date (build ${UpdateManager.installedBuild(context)})." else ""
                }.onFailure { status = "Could not check. Try again on Wi-Fi." }
            }
        }.start()
    }
    LaunchedEffect(Unit) { check() }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App updates", fontWeight = FontWeight.Bold)
            val i = info
            if (i != null) {
                Text("A new version is ready (build ${i.build}, ${i.size / 1_000_000} MB).", fontSize = 13.sp)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = progress < 0,
                    onClick = {
                        if (!context.packageManager.canRequestPackageInstalls()) {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            status = "Allow installs from TWIGA, come back and tap Update now."
                        } else {
                            progress = 0
                            Thread {
                                val r = runCatching { UpdateManager.install(context, i) { p -> main.post { progress = p } } }
                                main.post {
                                    progress = -1
                                    status = if (r.isSuccess) "Downloaded. Confirm the install when Android asks." else "Update failed: " + (r.exceptionOrNull()?.message ?: "try again")
                                }
                            }.start()
                        }
                    }
                ) { Text(if (progress >= 0) "Downloading $progress%" else "Update now") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Install updates automatically")
                    Text("Only when no call is in progress.", fontSize = 11.sp, color = MutedText)
                }
                Switch(checked = auto, onCheckedChange = { auto = it; UpdateManager.setAuto(context, it) })
            }
            OutlinedButton(onClick = { check() }, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
            if (status.isNotBlank()) Text(status, fontSize = 12.sp, color = MutedText)
        }
    }
}
