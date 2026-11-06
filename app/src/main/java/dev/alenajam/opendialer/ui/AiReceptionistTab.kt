package dev.alenajam.opendialer.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val Muted = Color(0xFF667085)
private val TYPES = listOf(
    "salon" to "Salon or barber", "shop" to "Shop or retail", "clinic" to "Clinic or pharmacy", "restaurant" to "Restaurant",
    "repair" to "Technician or repair", "legal" to "Law firm", "realestate" to "Real estate", "general" to "Other"
)
private val MODES = listOf(
    "When I don't answer" to "61", "When I am busy" to "67",
    "When my phone is off or has no signal" to "62", "Every call" to "21"
)

private fun normalizeLine(v: String): String? {
    val d = v.filter { it.isDigit() || it == '+' }
    val n = when {
        d.startsWith("+") -> d
        d.startsWith("00") -> "+" + d.drop(2)
        d.startsWith("0") -> "+254" + d.drop(1)
        else -> "+$d"
    }
    return if (n.length in 9..16) n else null
}

private fun fetchTemplate(type: String): JSONObject? = runCatching {
    val c = URL("https://app.twiga.co.ke/templates.json").openConnection() as HttpURLConnection
    try {
        c.connectTimeout = 8000
        c.readTimeout = 10000
        JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONObject(type)
    } finally {
        c.disconnect()
    }
}.getOrNull()

/** Empty spaces for what the AI knows and answers, plus call forwarding to the AI line. Works before pairing; syncs once paired. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiReceptionistTab() {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val sp = remember { context.getSharedPreferences("twiga_ai", Context.MODE_PRIVATE) }
    val paired = RemoteDesk.isPaired(context)
    var name by remember { mutableStateOf(sp.getString("name", "") ?: "") }
    var type by remember { mutableStateOf(sp.getString("type", "") ?: "") }
    var about by remember { mutableStateOf(sp.getString("about", "") ?: "") }
    var faq by remember { mutableStateOf(sp.getString("faq", "") ?: "") }
    var rules by remember { mutableStateOf(sp.getString("rules", "") ?: "") }
    var greeting by remember { mutableStateOf(sp.getString("greeting", "") ?: "") }
    var missed by remember { mutableStateOf(sp.getString("missed", "") ?: "") }
    var line by remember { mutableStateOf(sp.getString("line", "") ?: "") }
    var mode by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(paired) {
        if (paired && about.isBlank() && faq.isBlank() && rules.isBlank()) {
            val token = RemoteDesk.token(context)
            Thread {
                runCatching { JSONObject(RemoteDesk.rpc("device_get_ai", JSONObject().put("p_token", token))) }.onSuccess { o ->
                    main.post {
                        about = o.optString("prompt"); faq = o.optString("faq"); rules = o.optString("rules")
                        greeting = o.optString("greeting"); missed = o.optString("auto_reply")
                        if (line.isBlank()) line = o.optString("line")
                    }
                }
            }.start()
        }
    }

    fun save() {
        sp.edit().putString("name", name).putString("type", type).putString("about", about).putString("faq", faq)
            .putString("rules", rules).putString("greeting", greeting).putString("missed", missed).putString("line", line).apply()
        if (!paired) { note = "Saved on this phone. Connect the phone to the dashboard (Business tab) to share it."; return }
        note = "Saving..."
        val token = RemoteDesk.token(context)
        val n = normalizeLine(line)
        Thread {
            val r = runCatching {
                RemoteDesk.rpc(
                    "device_save_ai",
                    JSONObject().put("p_token", token).put("p_prompt", about).put("p_greeting", greeting)
                        .put("p_auto_reply", missed).put("p_faq", faq).put("p_rules", rules)
                )
                if (n != null) RemoteDesk.rpc("device_save_line", JSONObject().put("p_token", token).put("p_number", n))
            }
            main.post {
                note = if (r.isSuccess) "Saved. The dashboard and your AI phone line use it too."
                else "Saved on this phone, but not shared yet: " + (r.exceptionOrNull()?.message ?: "check the internet")
            }
        }.start()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AI receptionist", fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("Tell your AI about your business and what to answer. It starts empty, so write it your way.", color = Muted)

        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Business name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Type of business", fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TYPES.forEach { (k, l) -> FilterChip(selected = type == k, onClick = { type = k }, label = { Text(l) }) }
                }
                OutlinedButton(
                    enabled = type.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        note = "Loading starter text..."
                        val k = type
                        Thread {
                            val t = fetchTemplate(k)
                            main.post {
                                if (t == null) note = "Could not load the starter text. Check the internet."
                                else {
                                    if (about.isBlank()) about = t.optString("about")
                                    if (faq.isBlank()) faq = t.optString("faq")
                                    if (rules.isBlank()) rules = t.optString("rules")
                                    note = "Added to the empty boxes. Replace the [brackets] with your details, then Save."
                                }
                            }
                        }.start()
                    }
                ) { Text("Add starter text for this business type") }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    about, { about = it.take(20000) },
                    label = { Text("About my business: paste anything the AI should know") },
                    placeholder = { Text("Services, prices, hours, location, delivery, payment...") },
                    minLines = 8, maxLines = 16, modifier = Modifier.fillMaxWidth()
                )
                Text("${about.length} / 20000", fontSize = 11.sp, color = Muted)
                OutlinedTextField(
                    faq, { faq = it.take(10000) },
                    label = { Text("What the AI should answer: questions and answers") },
                    placeholder = { Text("Q: What time do you open?\nA: 8am to 7pm.") },
                    minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    rules, { rules = it.take(3000) },
                    label = { Text("Rules: what the AI must never do") },
                    minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(greeting, { greeting = it.take(500) }, label = { Text("Greeting (optional)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(missed, { missed = it.take(500) }, label = { Text("Message for missed calls") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
                OutlinedButton(
                    onClick = { save(); context.startActivity(Intent(context, AiCallActivity::class.java)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save and talk to my AI") }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Forward calls to my AI", fontWeight = FontWeight.Bold)
                Text("Your AI answers on its own phone number. Forward your calls to it when you cannot or do not want to answer. Your carrier may charge for forwarded calls.", fontSize = 12.sp, color = Muted)
                OutlinedTextField(
                    line, { line = it.filter { c -> c.isDigit() || c == '+' }.take(16) },
                    label = { Text("My AI phone number (with country code)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                MODES.forEachIndexed { i, m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == i, onClick = { mode = i })
                        Text(m.first, fontSize = 13.sp)
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val n = normalizeLine(line)
                        if (n == null) note = "Enter your AI phone number first."
                        else {
                            val code = MODES[mode].second
                            dialCode(context, "**" + code + "*" + n + (if (code == "61") "**20" else "") + "#")
                            note = "Your phone is sending the forwarding code. Wait for the confirmation message from your network."
                        }
                    }
                ) { Text("Turn forwarding on") }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { dialCode(context, "##002#"); note = "Turning off all call forwarding. Wait for the confirmation message." }
                ) { Text("Turn all forwarding off") }
                Text("To test, call your own number from another phone while forwarding is on.", fontSize = 11.sp, color = Muted)
            }
        }
        if (note.isNotBlank()) Text(note, fontSize = 13.sp, color = Muted)
    }
}
