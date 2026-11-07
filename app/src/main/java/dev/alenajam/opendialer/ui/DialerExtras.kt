package dev.alenajam.opendialer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Button
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
import androidx.core.content.ContextCompat
import dev.alenajam.opendialer.remote.RemoteDesk
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Saves the AI switch on the phone and tells the dashboard if this phone is paired. */
fun setAi(context: Context, on: Boolean) {
    RemoteDesk.prefs(context).edit().putBoolean("ai", on).apply()
    val token = RemoteDesk.token(context)
    if (token.isNotBlank()) {
        Thread { runCatching { RemoteDesk.rpc("device_set_ai", JSONObject().put("p_token", token).put("p_enabled", on)) } }.start()
    }
}

/** Opens a USSD or dial code. Calls straight away when the phone permission is granted, otherwise opens the dialer filled in. */
fun dialCode(context: Context, code: String) {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    val intent = Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(code)))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Small AI ON / AI OFF button that sits in the dialer's top bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiToggleAction() {
    val context = LocalContext.current
    var ai by remember { mutableStateOf(RemoteDesk.aiEnabled(context)) }
    LaunchedEffect(Unit) {
        while (true) { ai = RemoteDesk.aiEnabled(context); delay(2000) }
    }
    FilterChip(
        selected = ai,
        onClick = { ai = !ai; setAi(context, ai) },
        label = { Text(if (ai) "AI ON" else "AI OFF", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFFF47721),
            selectedLabelColor = Color.Black
        )
    )
}

/** The Business tab: AI switch, quick shortcuts, orders and stock, and the dashboard link. */
@Composable
fun BusinessTab() {
    val context = LocalContext.current
    var ai by remember { mutableStateOf(RemoteDesk.aiEnabled(context)) }
    var autoAnswer by remember { mutableStateOf(RemoteDesk.autoAnswerEnabled(context)) }
    LaunchedEffect(Unit) {
        while (true) { ai = RemoteDesk.aiEnabled(context); delay(2000) }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AiTalkCard()
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI receptionist", fontWeight = FontWeight.Bold)
                    Text(if (ai) "On. Incoming calls are tagged for the AI." else "Off. Calls are handled by you.", fontSize = 12.sp, color = Color(0xFF667085))
                }
                Switch(checked = ai, onCheckedChange = { ai = it; setAi(context, it) })
            }
        }
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-answer incoming calls", fontWeight = FontWeight.Bold)
                    Text("When enabled, TWIGA answers after 1.2 seconds while it is your default phone app. This answers the SIM call on the handset; it does not connect the call audio to the cloud AI.", fontSize = 11.sp, color = MutedText)
                }
                Switch(checked = autoAnswer, onCheckedChange = {
                    autoAnswer = it
                    RemoteDesk.setAutoAnswerEnabled(context, it)
                })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { dialCode(context, "*334#") }, modifier = Modifier.weight(1f)) { Text("M-Pesa / Airtime") }
            OutlinedButton(
                onClick = { context.startActivity(Intent(context, ReceptionistActivity::class.java)) },
                modifier = Modifier.weight(1f)
            ) { Text("Orders & customers") }
        }
        Button(onClick = { context.startActivity(Intent(context, PosActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open TWIGA POS")
        }
        RemoteDeskCard()
        AiSettingsCard()
        WhatsAppSoonCard()
        UpdateCard()
    }
}
