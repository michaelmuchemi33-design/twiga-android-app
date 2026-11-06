package dev.alenajam.opendialer.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.alenajam.opendialer.remote.RemoteDesk
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Keep the receptionist switch in sync with the paired TWIGA account. */
fun setAi(context: Context, on: Boolean) {
    RemoteDesk.prefs(context).edit().putBoolean("ai", on).apply()
    val token = RemoteDesk.token(context)
    if (token.isNotBlank()) {
        Thread {
            runCatching {
                RemoteDesk.rpc("device_set_ai", JSONObject().put("p_token", token).put("p_enabled", on))
            }
        }.start()
    }
}

/** Open a USSD or dial code using Android's normal phone permission path. */
fun dialCode(context: Context, code: String) {
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CALL_PHONE
    ) == PackageManager.PERMISSION_GRANTED
    val intent = Intent(
        if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL,
        Uri.parse("tel:" + Uri.encode(code))
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Native AI control remains available beside the dialer's search bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiToggleAction() {
    val context = LocalContext.current
    var ai by remember { mutableStateOf(RemoteDesk.aiEnabled(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            ai = RemoteDesk.aiEnabled(context)
            delay(2000)
        }
    }
    FilterChip(
        selected = ai,
        onClick = {
            ai = !ai
            setAi(context, ai)
        },
        label = {
            Text(
                if (ai) "AI ON" else "AI OFF",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFFF47721),
            selectedLabelColor = Color(0xFF111111)
        )
    )
}

/** The business tab is the live TWIGA app, not a parallel sample dashboard. */
@Composable
fun BusinessTab(
    authCallback: Uri? = null,
    onAuthCallbackConsumed: () -> Unit = {}
) {
    TwigaDashboardTab(
        authCallback = authCallback,
        onAuthCallbackConsumed = onAuthCallbackConsumed
    )
}
