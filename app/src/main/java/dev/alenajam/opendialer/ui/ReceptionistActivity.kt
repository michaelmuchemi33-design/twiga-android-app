package dev.alenajam.opendialer.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF111827)
private val Muted = Color(0xFF667085)
private val Canvas = Color(0xFFF5F6F8)
private val Blue = Color(0xFF2457D6)

class ReceptionistActivity : ComponentActivity() {
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Canvas, surface = Color.White)) {
            ReceptionistScreen(
                onOpenDialer = { startActivity(Intent(this, MainActivity::class.java)) },
                onDefaultDialer = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val manager = getSystemService(RoleManager::class.java)
                        if (manager.isRoleAvailable(RoleManager.ROLE_DIALER) && !manager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                            roleLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                        }
                    }
                }
            )
        } }
    }
}

@Composable
private fun ReceptionistScreen(onOpenDialer: () -> Unit, onDefaultDialer: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("unity_receptionist", Context.MODE_PRIVATE) }
    var business by remember { mutableStateOf(prefs.getString("business", "") ?: "") }
    var greeting by remember { mutableStateOf(prefs.getString("greeting", "Hello! Thanks for calling. How can I help you today?") ?: "") }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "") ?: "") }
    var assistantEnabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var tab by remember { mutableStateOf(0) }
    var previewInput by remember { mutableStateOf("") }
    var previewReply by remember { mutableStateOf("") }
    var team by remember { mutableStateOf(prefs.getString("team", "Reception|101|;Sales|102|;Accounts|103|;Manager|104|")!!.split(";").filter(String::isNotBlank)) }
    var rules by remember { mutableStateOf(prefs.getString("rules", "sales|102;accounts|103;urgent|104")!!.split(";").filter(String::isNotBlank)) }
    fun save() {
        prefs.edit().putString("business", business).putString("greeting", greeting)
            .putString("endpoint", endpoint).putBoolean("enabled", assistantEnabled)
            .putString("team", team.joinToString(";")).putString("rules", rules.joinToString(";")).apply()
    }
    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                listOf("Home" to Icons.Default.Home, "Routing" to Icons.Default.CallSplit, "Team" to Icons.Default.Groups, "Setup" to Icons.Default.Settings).forEachIndexed { index, item ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(item.second, null) }, label = { Text(item.first) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().background(Canvas).padding(padding)) {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, null, tint = Ink, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text("UNITY", fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = Ink); Text("AI RECEPTIONIST", fontSize = 11.sp, color = Muted) }
                Text(if (assistantEnabled && endpoint.startsWith("https://")) "READY" else "SETUP", color = if (assistantEnabled && endpoint.startsWith("https://")) Color(0xFF16794A) else Color(0xFF9A6500), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            when (tab) {
                0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Text("Your business front desk", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink); Text("A simple control panel for your business phone.", color = Muted) }
                    item { Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp)) {
                        Text("ASSISTANT STATUS", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(if (assistantEnabled) "Assistant switched on" else "Assistant paused", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("AI endpoint configuration only. Live SIM-call audio answering is not enabled by this app.", color = Color(0xFFD1D5DB), fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) { Switch(assistantEnabled, { assistantEnabled = it; save() }); Text(if (assistantEnabled) "Enabled" else "Paused", color = Color.White) }
                    } } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { StatCard("Team", team.size.toString(), Modifier.weight(1f)); StatCard("Rules", rules.size.toString(), Modifier.weight(1f)) } }
                    item { Button(onClick = onOpenDialer, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Dialpad, null); Spacer(Modifier.width(8.dp)); Text("Open phone dialer") } }
                    item { OutlinedButton(onClick = onDefaultDialer, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PhoneAndroid, null); Spacer(Modifier.width(8.dp)); Text("Set as default phone app") } }
                    item { Text("Important: Android does not give ordinary apps a universal way to take over existing SIM-call audio. The app can act as a dialer; AI answering needs a separately supported call path.", color = Muted, fontSize = 12.sp) }
                }
                1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Routing rules", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink); Text("Local keyword-to-extension examples.", color = Muted) }
                    items(rules) { rule -> val parts = rule.split("|"); Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(parts.firstOrNull().orEmpty(), fontWeight = FontWeight.SemiBold); Text("Extension " + parts.getOrElse(1) { "" }, color = Muted, fontSize = 12.sp) }; IconButton(onClick = { rules = rules - rule; save() }) { Icon(Icons.Default.Delete, null) } } } }
                    item { var word by remember { mutableStateOf("") }; var ext by remember { mutableStateOf("") }; Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(word, { word = it }, label = { Text("Keyword") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(ext, { ext = it }, label = { Text("Extension") }, modifier = Modifier.fillMaxWidth()); Button(onClick = { if (word.isNotBlank() && ext.isNotBlank()) { rules = rules + "${word.lowercase()}|$ext"; save(); word = ""; ext = "" } }, modifier = Modifier.fillMaxWidth()) { Text("Add rule") } } } }
                }
                2 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Your team", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink); Text("Add staff and internal extension labels.", color = Muted) }
                    items(team) { person -> val p = person.split("|"); Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(p.firstOrNull().orEmpty(), fontWeight = FontWeight.SemiBold); Text("Ext. " + p.getOrElse(1) { "" } + "  " + p.getOrElse(2) { "" }, color = Muted, fontSize = 12.sp) }; IconButton(onClick = { team = team - person; save() }) { Icon(Icons.Default.Delete, null) } } } }
                    item { var name by remember { mutableStateOf("") }; var ext by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }; Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Staff name / department") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(ext, { ext = it }, label = { Text("Extension") }, modifier = Modifier.fillMaxWidth()); OutlinedTextField(phone, { phone = it }, label = { Text("Phone number (optional)") }, modifier = Modifier.fillMaxWidth()); Button(onClick = { if (name.isNotBlank() && ext.isNotBlank()) { team = team + "$name|$ext|$phone"; save(); name = ""; ext = ""; phone = "" } }, modifier = Modifier.fillMaxWidth()) { Text("Add team member") } } } }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Business setup", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink) }
                    item { Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(business, { business = it; save() }, label = { Text("Business name") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(greeting, { greeting = it; save() }, label = { Text("Opening greeting") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        OutlinedTextField(endpoint, { endpoint = it; save() }, label = { Text("HTTPS AI backend URL (optional)") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("Save settings") }
                    } } }
                    item { Card(colors = CardDefaults.cardColors(containerColor = Color.White)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Try the receptionist logic", fontWeight = FontWeight.Bold)
                        OutlinedTextField(previewInput, { previewInput = it }, label = { Text("Example: I need to talk to sales") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { val hit = rules.firstOrNull { previewInput.contains(it.substringBefore("|"), true) }; previewReply = if (hit != null) "Suggested route: extension ${hit.substringAfter("|")}" else greeting }, modifier = Modifier.fillMaxWidth()) { Text("Test request") }
                        if (previewReply.isNotBlank()) Text(previewReply, color = Ink)
                    } } }
                    item { Text("Do not put provider API keys in this app. Store secrets on a secured backend.", color = Muted, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) { Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink); Text(label, color = Muted, fontSize = 12.sp) }
    }
}
