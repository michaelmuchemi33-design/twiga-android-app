package co.ke.twiga.businessassistant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val twigaGoogleScopes = listOf(
    "https://www.googleapis.com/auth/calendar.events.owned",
    "https://www.googleapis.com/auth/contacts.readonly",
    "https://www.googleapis.com/auth/spreadsheets",
    "https://www.googleapis.com/auth/drive.file"
)

@Composable
internal fun GoogleWorkspaceWorkspace(api: TwigaApi, session: TwigaSession, businessId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var sheetUrl by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("Connect the business owner's Google account to enable these tools.") }
    var resultText by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var customer by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("2026-11-10T15:00:00+03:00") }
    var end by remember { mutableStateOf("2026-11-10T15:30:00+03:00") }
    var notes by remember { mutableStateOf("") }

    fun showStatus() {
        scope.launch {
            busy = true
            try {
                val status = api.googleWorkspace(session, businessId, JSONObject().put("action", "status"))
                connected = status.optBoolean("connected")
                email = status.optString("googleEmail")
                sheetUrl = status.optString("spreadsheetUrl")
                info = if (connected) "Google connected: " + email else "Google is not connected for this business."
            } catch (e: Exception) { info = e.message ?: "Could not check Google connection." }
            busy = false
        }
    }

    fun completeConnect(code: String) {
        scope.launch {
            busy = true
            try {
                val saved = api.googleWorkspace(session, businessId,
                    JSONObject().put("action", "connect").put("authorizationCode", code).put("redirectUri", "postmessage"))
                if (!saved.optBoolean("connected")) throw IllegalStateException(saved.optString("message", "Google did not grant the required permissions."))
                connected = true
                email = saved.optString("googleEmail")
                info = "Google connected securely: " + email
            } catch (e: Exception) { info = e.message ?: "Google connection failed." }
            busy = false
        }
    }

    val authorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val auth = Identity.getAuthorizationClient(context)
                    .getAuthorizationResultFromIntent(result.data ?: throw IllegalStateException("Google returned no authorization result."))
                val code = auth.serverAuthCode
                if (code.isNullOrBlank()) info = "Google did not return a server authorization code."
                else completeConnect(code)
            } catch (e: Exception) { info = e.message ?: "Google authorization failed." }
        } else info = "Google authorization was cancelled."
    }

    fun connectGoogle() {
        scope.launch {
            busy = true
            try {
                val config = api.googleWorkspace(session, businessId, JSONObject().put("action", "oauth.config"))
                val clientId = config.optString("clientId")
                if (clientId.isBlank()) throw IllegalStateException("Google OAuth is not configured. Add GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET to Supabase function secrets.")
                val activity = context as? Activity ?: throw IllegalStateException("Google authorization needs an Android activity.")
                val request = AuthorizationRequest.builder()
                    .setRequestedScopes(twigaGoogleScopes.map { Scope(it) })
                    .requestOfflineAccess(clientId)
                    .build()
                Identity.getAuthorizationClient(activity).authorize(request)
                    .addOnSuccessListener { auth ->
                        if (auth.hasResolution()) {
                            val pending = auth.pendingIntent
                            if (pending == null) {
                                info = "Google approval screen was not returned."
                                busy = false
                            } else {
                                authorizationLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                            }
                        } else {
                            val code = auth.serverAuthCode
                            if (code.isNullOrBlank()) {
                                info = "Google did not return a server authorization code."
                                busy = false
                            } else completeConnect(code)
                        }
                    }
                    .addOnFailureListener { error ->
                        info = error.message ?: "Google authorization failed. Check Google Play services."
                        busy = false
                    }
            } catch (e: Exception) {
                info = e.message ?: "Could not start Google authorization."
                busy = false
            }
        }
    }

    fun runGoogleAction(action: String, extra: JSONObject = JSONObject()) {
        scope.launch {
            busy = true
            try {
                val payload = JSONObject(extra.toString()).put("action", action)
                val result = api.googleWorkspace(session, businessId, payload)
                when (action) {
                    "calendar.list" -> {
                        val rows = result.optJSONArray("items") ?: JSONArray()
                        resultText = "Loaded " + rows.length() + " calendar booking(s)."
                    }
                    "appointments.sync" -> resultText = "Synced " + result.optInt("synced") + " TWIGA appointment(s) to Calendar."
                    "sheets.export-orders" -> {
                        sheetUrl = result.optString("spreadsheetUrl", sheetUrl)
                        resultText = "Orders exported to Google Sheets."
                    }
                    "drive.export-calls" -> {
                        resultText = "Exported " + result.optInt("exported") + " call record(s) to Drive. " +
                            result.optString("note")
                        val file = result.optJSONObject("file")
                        val link = file?.optString("webViewLink").orEmpty()
                        if (link.isNotBlank()) resultText += " " + link
                    }
                    "contacts.list" -> {
                        val rows = result.optJSONArray("connections") ?: JSONArray()
                        resultText = "Loaded " + rows.length() + " Google contact(s)."
                    }
                    "drive.list" -> {
                        val rows = result.optJSONArray("files") ?: JSONArray()
                        resultText = "Found " + rows.length() + " Drive file(s) available to TWIGA."
                    }
                }
            } catch (e: Exception) { resultText = e.message ?: "Google Workspace action failed." }
            busy = false
        }
    }

    fun createBooking() {
        if (title.isBlank() || customer.isBlank() || start.isBlank() || end.isBlank()) {
            resultText = "Enter the booking title, customer and start/end time."
            return
        }
        scope.launch {
            busy = true
            try {
                val event = JSONObject()
                    .put("summary", title.trim())
                    .put("description", "Customer: " + customer.trim() + "\nPhone: " + phone.trim() + "\n" + notes.trim())
                    .put("start", JSONObject().put("dateTime", start.trim()).put("timeZone", "Africa/Nairobi"))
                    .put("end", JSONObject().put("dateTime", end.trim()).put("timeZone", "Africa/Nairobi"))
                val result = api.googleWorkspace(session, businessId, JSONObject().put("action", "calendar.create").put("event", event))
                val googleEvent = result.optJSONObject("event") ?: JSONObject()
                val record = JSONObject()
                    .put("customer_name", customer.trim())
                    .put("phone", phone.trim())
                    .put("scheduled_at", start.trim())
                    .put("notes", notes.trim())
                    .put("status", "scheduled")
                    .put("google_event_id", googleEvent.optString("id"))
                    .put("google_event_url", googleEvent.optString("htmlLink"))
                    .put("google_synced_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(java.util.Date()))
                api.add("twiga_appointments", session, businessId, record)
                resultText = "Booking created in Google Calendar and saved in TWIGA."
                title = ""; customer = ""; phone = ""; notes = ""
            } catch (e: Exception) { resultText = e.message ?: "Booking could not be created. Check the date/time format." }
            busy = false
        }
    }

    LaunchedEffect(session.token, businessId) { showStatus() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("Google Workspace", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("Calendar bookings, contacts, Sheets and Drive exports.", fontSize = 13.sp, color = Muted)
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(15.dp)) {
                Text(if (connected) "Google connected" else "Connect Google", fontWeight = FontWeight.Bold)
                if (email.isNotBlank()) Text(email, fontSize = 12.sp, color = Muted)
                Text(info, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(vertical = 6.dp))
                if (connected) {
                    OutlinedButton(onClick = { runGoogleAction("disconnect") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Disconnect Google")
                    }
                    if (sheetUrl.isNotBlank()) TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sheetUrl)))
                    }) { Text("Open TWIGA Google Sheet") }
                } else {
                    Button(onClick = { connectGoogle() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Connect business Google account")
                    }
                }
            }
        }
        if (connected) {
            Spacer(Modifier.height(12.dp))
            Text("Create a booking", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Appointment title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(customer, { customer = it }, label = { Text("Customer name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(phone, { phone = it }, label = { Text("Customer phone") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(start, { start = it }, label = { Text("Start ISO time (+03:00)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(end, { end = it }, label = { Text("End ISO time (+03:00)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Button(onClick = { createBooking() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CalendarMonth, null); Spacer(Modifier.width(6.dp)); Text("Book in Google Calendar")
                    }
                    OutlinedButton(onClick = { runGoogleAction("calendar.list") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Refresh Google Calendar") }
                    OutlinedButton(onClick = { runGoogleAction("appointments.sync") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Sync, null); Spacer(Modifier.width(6.dp)); Text("Sync existing TWIGA appointments")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Business data", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    OutlinedButton(onClick = { runGoogleAction("contacts.list") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Contacts, null); Spacer(Modifier.width(6.dp)); Text("Load Google Contacts")
                    }
                    OutlinedButton(onClick = { runGoogleAction("sheets.export-orders") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(6.dp)); Text("Export orders to Google Sheets")
                    }
                    OutlinedButton(onClick = { runGoogleAction("drive.export-calls") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(6.dp)); Text("Export call records to Google Drive")
                    }
                    OutlinedButton(onClick = { runGoogleAction("drive.list") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Show TWIGA Drive files")
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
        if (resultText.isNotBlank()) Text(resultText, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 10.dp))
        Text("Each business owner connects their own Google account. TWIGA stores OAuth tokens encrypted; the owner can disconnect at any time.", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 12.dp))
    }
}
