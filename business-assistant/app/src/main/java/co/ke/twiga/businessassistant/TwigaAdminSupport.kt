package co.ke.twiga.businessassistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private data class PlatformClient(
    val id: String, val name: String, val type: String, val status: String,
    val paidThrough: String, val ownerEmail: String, val members: Int
)
private data class PlatformPayment(
    val id: String, val businessName: String, val amount: Double, val method: String,
    val reference: String, val paidAt: String, val note: String
)
private data class PlatformTicket(
    val id: String, val subject: String, val message: String, val contact: String,
    val status: String, val channel: String, val createdAt: String
)
private data class SupportLine(val role: String, val content: String)

private object TwigaAdminSupportApi {
    private val client = OkHttpClient.Builder().callTimeout(35, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    private suspend fun post(
        url: String, key: String, session: TwigaSession, function: String, body: JSONObject
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url.trimEnd('/') + "/functions/v1/" + function)
            .header("apikey", key)
            .header("Authorization", "Bearer ${session.token}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(json))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val parsed = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) {
                throw IllegalStateException(parsed.optString("error").ifBlank { "Server error ${response.code}" })
            }
            parsed
        }
    }

    suspend fun isPlatformAdmin(url: String, key: String, session: TwigaSession): Boolean {
        val result = post(url, key, session, "twiga-platform-admin", JSONObject().put("action", "whoami"))
        return result.optBoolean("isAdmin", false)
    }

    suspend fun adminOverview(url: String, key: String, session: TwigaSession): JSONObject =
        post(url, key, session, "twiga-platform-admin", JSONObject().put("action", "overview"))

    suspend fun recordPayment(
        url: String, key: String, session: TwigaSession, businessId: String,
        amount: Double, method: String, reference: String, note: String, paidThrough: String
    ): JSONObject {
        val body = JSONObject().put("action", "record_payment").put("businessId", businessId)
            .put("amount", amount).put("method", method.lowercase())
            .put("reference", reference).put("notes", note)
        if (paidThrough.isNotBlank()) body.put("periodEnd", paidThrough)
        return post(url, key, session, "twiga-platform-admin", body)
    }

    suspend fun setAccess(
        url: String, key: String, session: TwigaSession, businessId: String,
        status: String, paidThrough: String
    ): JSONObject {
        val body = JSONObject().put("action", "set_access").put("businessId", businessId).put("status", status)
        if (paidThrough.isNotBlank()) body.put("paidThrough", paidThrough)
        return post(url, key, session, "twiga-platform-admin", body)
    }

    suspend fun updateTicket(
        url: String, key: String, session: TwigaSession, ticketId: String, status: String
    ): JSONObject = post(url, key, session, "twiga-platform-admin",
        JSONObject().put("action", "update_ticket").put("ticketId", ticketId).put("status", status))

    suspend fun chat(
        url: String, key: String, session: TwigaSession?, businessId: String?,
        messages: List<SupportLine>
    ): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("action", "chat")
            .put("messages", JSONArray().apply {
                messages.takeLast(8).forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
        if (!businessId.isNullOrBlank()) body.put("businessId", businessId)
        val requestBuilder = Request.Builder()
            .url(url.trimEnd('/') + "/functions/v1/twiga-ai-support")
            .header("apikey", key).header("Content-Type", "application/json")
        if (session != null) requestBuilder.header("Authorization", "Bearer ${session.token}")
        val request = requestBuilder.post(body.toString().toRequestBody(json)).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val parsed = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) throw IllegalStateException(
                parsed.optString("error").ifBlank { "Support service error ${response.code}" }
            )
            parsed.optString("answer").ifBlank { "I could not prepare an answer. Please open a support ticket." }
        }
    }

    suspend fun createTicket(
        url: String, key: String, session: TwigaSession?, businessId: String?,
        subject: String, message: String, contact: String, channel: String
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("action", "ticket").put("subject", subject)
            .put("message", message).put("contact", contact).put("channel", channel)
        if (!businessId.isNullOrBlank()) body.put("businessId", businessId)
        val requestBuilder = Request.Builder()
            .url(url.trimEnd('/') + "/functions/v1/twiga-ai-support")
            .header("apikey", key).header("Content-Type", "application/json")
        if (session != null) requestBuilder.header("Authorization", "Bearer ${session.token}")
        val request = requestBuilder.post(body.toString().toRequestBody(json)).build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val parsed = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) throw IllegalStateException(
                parsed.optString("error").ifBlank { "Ticket service error ${response.code}" }
            )
            parsed
        }
    }
}

@Composable
fun TwigaPlatformAdminWorkspace(url: String, key: String, session: TwigaSession) {
    var clients by remember { mutableStateOf(emptyList<PlatformClient>()) }
    var payments by remember { mutableStateOf(emptyList<PlatformPayment>()) }
    var tickets by remember { mutableStateOf(emptyList<PlatformTicket>()) }
    var clientCount by remember { mutableIntStateOf(0) }
    var paidTotal by remember { mutableDoubleStateOf(0.0) }
    var openTickets by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var showPayment by remember { mutableStateOf(false) }
    var selectedBusiness by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("mpesa") }
    var reference by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var paidThrough by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun refresh() {
        loading = true
        scope.launch {
            try {
                val result = TwigaAdminSupportApi.adminOverview(url, key, session)
                val clientRows = result.optJSONArray("clients") ?: JSONArray()
                clients = (0 until clientRows.length()).map { i ->
                    val j = clientRows.getJSONObject(i)
                    PlatformClient(
                        j.optString("id"), j.optString("name"), j.optString("businessType"),
                        j.optString("status"), j.optString("paidThrough"), j.optString("ownerEmail"),
                        j.optInt("memberCount")
                    )
                }
                val paymentRows = result.optJSONArray("payments") ?: JSONArray()
                payments = (0 until paymentRows.length()).map { i ->
                    val j = paymentRows.getJSONObject(i)
                    PlatformPayment(
                        j.optString("id"), j.optJSONObject("businesses")?.optString("name").orEmpty(),
                        j.optDouble("amount"), j.optString("method"), j.optString("provider_reference"),
                        j.optString("paid_at"), j.optString("notes")
                    )
                }
                val ticketRows = result.optJSONArray("tickets") ?: JSONArray()
                tickets = (0 until ticketRows.length()).map { i ->
                    val j = ticketRows.getJSONObject(i)
                    PlatformTicket(
                        j.optString("id"), j.optString("subject"), j.optString("message"),
                        j.optString("contact"), j.optString("status"), j.optString("channel"),
                        j.optString("created_at")
                    )
                }
                val metrics = result.optJSONObject("metrics") ?: JSONObject()
                clientCount = metrics.optInt("clientCount")
                paidTotal = metrics.optDouble("paidTotalKes")
                openTickets = metrics.optInt("openTickets")
                if (selectedBusiness.isBlank()) selectedBusiness = clients.firstOrNull()?.id.orEmpty()
                error = ""
            } catch (e: Exception) { error = e.message ?: "Could not load platform data." }
            loading = false
        }
    }

    LaunchedEffect(session.token) { refresh() }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Platform admin", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text("Clients, one-off payments and support", fontSize = 12.sp, color = Muted)
            }
            TextButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, null); Text("Refresh") }
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFB3261E), modifier = Modifier.padding(vertical = 8.dp))
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Clients", clientCount.toString(), Modifier.weight(1f))
            MetricCard("Collected", "KSh ${"%.0f".format(paidTotal)}", Modifier.weight(1f))
            MetricCard("Open support", openTickets.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Client accounts", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Button(onClick = { showPayment = true }, enabled = clients.isNotEmpty()) {
                Icon(Icons.Default.Add, null); Text("Record payment")
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(clients, key = { it.id }) { client ->
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(client.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                if (client.ownerEmail.isNotBlank()) Text(client.ownerEmail, color = Muted, fontSize = 12.sp)
                                Text("${client.type.ifBlank { "Business" }} • ${client.members} member(s)", color = Muted, fontSize = 11.sp)
                            }
                            StatusPill(client.status)
                        }
                        Text("Paid through: ${client.paidThrough.ifBlank { "Not set" }}", color = Muted, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                scope.launch {
                                    try { TwigaAdminSupportApi.setAccess(url, key, session, client.id, "ACTIVE", ""); refresh() }
                                    catch (e: Exception) { error = e.message ?: "Could not activate client." }
                                }
                            }) { Text("Activate") }
                            TextButton(onClick = {
                                scope.launch {
                                    try { TwigaAdminSupportApi.setAccess(url, key, session, client.id, "SUSPENDED", ""); refresh() }
                                    catch (e: Exception) { error = e.message ?: "Could not suspend client." }
                                }
                            }) { Text("Suspend", color = Color(0xFFB3261E)) }
                        }
                    }
                }
            }
            item { Text("Recent payments", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) }
            if (payments.isEmpty()) item { Text("No payments recorded yet.", color = Muted) }
            items(payments, key = { it.id }) { payment ->
                Card(Modifier.fillMaxWidth().padding(bottom = 7.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(payment.businessName.ifBlank { "Client payment" }, fontWeight = FontWeight.SemiBold)
                            Text("${payment.method.uppercase()} • ${payment.reference.ifBlank { "No reference" }}", fontSize = 11.sp, color = Muted)
                        }
                        Text("KSh ${"%.2f".format(payment.amount)}", color = Orange, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item { Text("Support inbox", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) }
            if (tickets.isEmpty()) item { Text("No support tickets yet.", color = Muted) }
            items(tickets, key = { it.id }) { ticket ->
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ticket.subject, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            StatusPill(ticket.status)
                        }
                        Text(ticket.message, fontSize = 12.sp, color = Muted)
                        if (ticket.contact.isNotBlank()) Text(ticket.contact, fontSize = 11.sp)
                        Row {
                            TextButton(onClick = {
                                scope.launch {
                                    try { TwigaAdminSupportApi.updateTicket(url, key, session, ticket.id, "in_progress"); refresh() }
                                    catch (e: Exception) { error = e.message ?: "Could not update ticket." }
                                }
                            }) { Text("Working") }
                            TextButton(onClick = {
                                scope.launch {
                                    try { TwigaAdminSupportApi.updateTicket(url, key, session, ticket.id, "resolved"); refresh() }
                                    catch (e: Exception) { error = e.message ?: "Could not resolve ticket." }
                                }
                            }) { Text("Resolve") }
                        }
                    }
                }
            }
        }
    }

    if (showPayment) {
        AlertDialog(
            onDismissRequest = { showPayment = false },
            title = { Text("Record one-off payment") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                    Text("Choose the client", fontWeight = FontWeight.SemiBold)
                    clients.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { selectedBusiness = c.id }) {
                            RadioButton(selected = selectedBusiness == c.id, onClick = { selectedBusiness = c.id })
                            Text(c.name)
                        }
                    }
                    OutlinedTextField(amount, { amount = it }, label = { Text("Amount (KSh)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(method, { method = it }, label = { Text("Method: mpesa, bank, cash, card, other") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(reference, { reference = it }, label = { Text("M-Pesa / bank reference (optional)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(paidThrough, { paidThrough = it }, label = { Text("Paid through date YYYY-MM-DD (optional)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    Text("This records a payment only. It does not create an automatic subscription or renewals.", fontSize = 11.sp, color = Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = amount.toDoubleOrNull()
                    if (selectedBusiness.isBlank() || parsed == null || parsed <= 0) { error = "Choose a client and enter a valid amount." }
                    else scope.launch {
                        try {
                            TwigaAdminSupportApi.recordPayment(url, key, session, selectedBusiness, parsed, method, reference, note, paidThrough)
                            amount = ""; reference = ""; note = ""; paidThrough = ""; showPayment = false; refresh()
                        } catch (e: Exception) { error = e.message ?: "Payment could not be saved." }
                    }
                }) { Text("Save payment") }
            },
            dismissButton = { TextButton(onClick = { showPayment = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(label, fontSize = 10.sp, color = Muted)
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ink)
        }
    }
}

@Composable
private fun StatusPill(value: String) {
    val color = when (value.uppercase()) {
        "ACTIVE", "RESOLVED" -> Color(0xFF26734D)
        "SUSPENDED" -> Color(0xFFB3261E)
        else -> Orange
    }
    Surface(color = color.copy(alpha = 0.10f), shape = RoundedCornerShape(30.dp)) {
        Text(value.ifBlank { "UNKNOWN" }.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
    }
}

@Composable
fun TwigaSupportWorkspace(url: String, key: String, session: TwigaSession?, businessId: String?) {
    var messages by remember { mutableStateOf(listOf(SupportLine("assistant", "Hello! I’m TWIGA Support. Tell me what you need help with."))) }
    var question by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var showTicket by remember { mutableStateOf(false) }
    var subject by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Text("TWIGA Support", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("Ask about setup, payments, voice, products or troubleshooting.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.role == "user") Arrangement.End else Arrangement.Start) {
                    Surface(
                        color = if (line.role == "user") Orange.copy(alpha = 0.14f) else Color.White,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(line.content, modifier = Modifier.widthIn(max = 300.dp).padding(12.dp), color = Ink, fontSize = 13.sp)
                    }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        if (error.isNotBlank()) Text(error, color = Color(0xFFB3261E), fontSize = 12.sp)
        OutlinedTextField(
            value = question, onValueChange = { question = it },
            modifier = Modifier.fillMaxWidth(), placeholder = { Text("Write your question…") },
            maxLines = 3
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showTicket = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.SupportAgent, null); Text("Human support") }
            Button(onClick = {
                val text = question.trim()
                if (text.isNotBlank() && !busy) {
                    val next = messages + SupportLine("user", text)
                    messages = next; question = ""; busy = true; error = ""
                    scope.launch {
                        try {
                            val answer = TwigaAdminSupportApi.chat(url, key, session, businessId, next)
                            messages = messages + SupportLine("assistant", answer)
                        } catch (e: Exception) {
                            error = e.message ?: "AI support is not available. Open a human support ticket."
                        }
                        busy = false
                    }
                }
            }, enabled = !busy && question.isNotBlank(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Send, null); Text("Send")
            }
        }
    }

    if (showTicket) {
        AlertDialog(
            onDismissRequest = { showTicket = false },
            title = { Text("Contact human support") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(subject, { subject = it }, label = { Text("Subject") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(details, { details = it }, label = { Text("Describe the issue") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    OutlinedTextField(contact, { contact = it }, label = { Text("Your email or WhatsApp number (optional)") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (subject.isBlank() || details.isBlank() || busy) error = "Enter a subject and describe the issue."
                    else {
                        busy = true
                        scope.launch {
                            try {
                                val result = TwigaAdminSupportApi.createTicket(url, key, session, businessId, subject, details, contact, "app")
                                val ticket = result.optJSONObject("ticket")
                                messages = messages + SupportLine("assistant", "Your support request was saved. Reference: ${ticket?.optString("id").orEmpty()}")
                                showTicket = false; subject = ""; details = ""; contact = ""; error = ""
                            } catch (e: Exception) { error = e.message ?: "Could not open a support ticket." }
                            busy = false
                        }
                    }
                }) { Text("Submit ticket") }
            },
            dismissButton = { TextButton(onClick = { showTicket = false }) { Text("Cancel") } }
        )
    }
}
