package co.ke.twiga.businessassistant

import android.Manifest
import android.content.pm.PackageManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit

internal object TwigaRetellApi {
    private val client = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()

    private suspend fun post(
        url: String, key: String, session: TwigaSession, action: String, businessId: String, extra: JSONObject = JSONObject()
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject(extra.toString()).put("action", action).put("businessId", businessId)
        val request = Request.Builder()
            .url(url.trimEnd('/') + "/functions/v1/twiga-retell")
            .header("apikey", key)
            .header("Authorization", "Bearer " + session.token)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val parsed = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            if (!response.isSuccessful) {
                throw IllegalStateException(parsed.optString("error").ifBlank { "Retell request failed (" + response.code + ")" })
            }
            parsed
        }
    }

    suspend fun getConfig(url: String, key: String, session: TwigaSession, businessId: String) =
        post(url, key, session, "get_config", businessId)

    suspend fun saveKey(url: String, key: String, session: TwigaSession, businessId: String, apiKey: String) =
        post(url, key, session, "save_key", businessId, JSONObject().put("apiKey", apiKey))

    suspend fun linkExistingAgent(url: String, key: String, session: TwigaSession, businessId: String, agentId: String) =
        post(url, key, session, "link_existing_agent", businessId, JSONObject().put("agentId", agentId))

    suspend fun listPhoneNumbers(url: String, key: String, session: TwigaSession, businessId: String) =
        post(url, key, session, "list_phone_numbers", businessId)

    suspend fun saveSettings(
        url: String, key: String, session: TwigaSession, businessId: String,
        provider: String, voiceId: String, voiceModel: String, model: String,
        ambientVolume: Double, inboundPrompt: String, outboundPrompt: String,
        recordingEnabled: Boolean, recordingNotice: String, bridgeNumber: String
    ) = post(url, key, session, "save_settings", businessId, JSONObject()
        .put("provider", provider).put("voiceId", voiceId).put("voiceModel", voiceModel)
        .put("model", model).put("ambientVolume", ambientVolume)
        .put("inboundPrompt", inboundPrompt).put("outboundPrompt", outboundPrompt)
        .put("recordingEnabled", recordingEnabled).put("recordingNotice", recordingNotice)
        .put("bridgeNumber", bridgeNumber))

    suspend fun provision(url: String, key: String, session: TwigaSession, businessId: String) =
        post(url, key, session, "provision_agent", businessId)

    suspend fun createWebCall(url: String, key: String, session: TwigaSession, businessId: String) =
        post(url, key, session, "create_web_call", businessId)
}

@Composable
internal fun TwigaRetellWorkspace(
    url: String,
    key: String,
    session: TwigaSession,
    businessId: String,
    businessName: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var configLoaded by remember { mutableStateOf(false) }
    var keyConfigured by remember { mutableStateOf(false) }
    var keyLast4 by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var recordingEnabled by remember { mutableStateOf(false) }
    var recordingNotice by remember { mutableStateOf("This call may be recorded to help us serve you.") }
    var provider by remember { mutableStateOf("vapi") }
    var voiceId by remember { mutableStateOf("retell-Willa") }
    var voiceModel by remember { mutableStateOf("") }
    var existingAgentId by remember { mutableStateOf("") }
    var bridgeNumber by remember { mutableStateOf("") }
    var retellNumbers by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var model by remember { mutableStateOf("gpt-6-luna") }
    var ambient by remember { mutableFloatStateOf(0.12f) }
    var inboundPrompt by remember { mutableStateOf("") }
    var outboundPrompt by remember { mutableStateOf("You are calling on behalf of {{business_name}}. Your reason for calling is {{call_reason}}. Identify yourself and the business, ask whether it is a convenient time, speak naturally in English or Kiswahili, never invent facts or prices, and confirm any booking or order only after TWIGA confirms it was saved. If the person asks not to be called again, politely acknowledge and end the call.") }
    var agentId by remember { mutableStateOf("") }
    var llmId by remember { mutableStateOf("") }
    var accessToken by remember { mutableStateOf("") }
    var webReady by remember { mutableStateOf(false) }
    var callStatus by remember { mutableStateOf("Not connected") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var permissionPending by remember { mutableStateOf(false) }

    fun requestWebCall() {
        loading = true
        error = ""
        message = ""
        scope.launch {
            try {
                val result = TwigaRetellApi.createWebCall(url, key, session, businessId)
                accessToken = result.optString("accessToken")
                callStatus = "Connecting to TWIGA assistant…"
                webReady = false
                if (accessToken.isBlank()) error = "Retell did not return a call token."
            } catch (e: Exception) {
                error = e.message ?: "Could not start the Retell call."
            } finally {
                loading = false
            }
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        if (granted) requestWebCall() else error = "Microphone permission is required for the in-app AI voice call."
    }

    fun startCall() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            requestWebCall()
        } else {
            permissionPending = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(businessId, session.token) {
        loading = true
        error = ""
        try {
            val result = TwigaRetellApi.getConfig(url, key, session, businessId)
            val config = result.optJSONObject("config") ?: JSONObject()
            provider = config.optString("provider", "vapi")
            keyConfigured = result.optBoolean("keyConfigured", false)
            keyLast4 = result.optString("keyLast4", "")
            recordingEnabled = result.optBoolean("recordingEnabled", false)
            recordingNotice = result.optString("recordingNotice", "This call may be recorded to help us serve you.")
            voiceId = config.optString("retell_voice_id", "retell-Willa")
            voiceModel = config.optString("retell_voice_model", "")
            existingAgentId = config.optString("retell_agent_id", "")
            bridgeNumber = result.optString("aiBridgeNumber", "")
            model = config.optString("retell_model", "gpt-6-luna")
            ambient = config.optDouble("retell_ambient_sound_volume", 0.12).toFloat().coerceIn(0f, 0.3f)
            inboundPrompt = config.optString("inbound_prompt", "")
            outboundPrompt = config.optString("outbound_prompt", outboundPrompt)
            agentId = config.optString("retell_agent_id", "")
            llmId = config.optString("retell_llm_id", "")
            configLoaded = true
        } catch (e: Exception) {
            error = e.message ?: "Could not load AI settings."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(accessToken, webReady) {
        if (accessToken.isNotBlank() && webReady) {
            val token = JSONObject.quote(accessToken)
            webView?.evaluateJavascript("window.setTwigaRetellToken && window.setTwigaRetellToken(" + token + ")", null)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Voice & AI settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(businessName + " · This configuration belongs only to this business.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Voice provider", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = provider == "retell", onClick = { provider = "retell" }, label = { Text("Retell · Willa") })
                    FilterChip(selected = provider == "vapi", onClick = { provider = "vapi" }, label = { Text("Vapi · existing") })
                }
                Text("Vapi is retained. Selecting Retell switches this business's next TWIGA voice sessions after an agent is provisioned.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Retell API connection", fontWeight = FontWeight.SemiBold)
                Text(
                    if (keyConfigured) "Connected · key ending " + keyLast4 + " · stored securely"
                    else "Not connected for this business",
                    color = if (keyConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    apiKey, { apiKey = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Retell API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text("Paste a rotated Retell key") }
                )
                Button(
                    onClick = {
                        loading = true; error = ""; message = ""
                        scope.launch {
                            try {
                                val result = TwigaRetellApi.saveKey(url, key, session, businessId, apiKey.trim())
                                keyConfigured = result.optBoolean("saved", false)
                                keyLast4 = result.optString("keyLast4", "")
                                apiKey = ""
                                message = "Retell account connected. The API key was encrypted and will not be shown again."
                            } catch (e: Exception) { error = e.message ?: "Could not save Retell key." }
                            finally { loading = false }
                        }
                    },
                    enabled = apiKey.isNotBlank() && !loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Validate & securely save key") }
                Text("Only the business owner or TWIGA platform admin can replace this key. The app never stores the private key locally. In Retell Settings > API Keys, mark this same key as the Webhook Key so call events can be verified. Restricted keys need Build > Agent > Edit and Deploy > Call > Edit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Existing Retell agent & SIM bridge number", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    existingAgentId, { existingAgentId = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Existing Retell agent ID") }, singleLine = true
                )
                Button(
                    onClick = {
                        loading = true; error = ""; message = ""
                        scope.launch {
                            try {
                                val linked = TwigaRetellApi.linkExistingAgent(url, key, session, businessId, existingAgentId.trim())
                                agentId = linked.optJSONObject("config")?.optString("retell_agent_id", existingAgentId) ?: existingAgentId
                                llmId = linked.optJSONObject("config")?.optString("retell_llm_id", "") ?: ""
                                provider = "retell"
                                message = "Existing Retell agent linked to " + businessName + ". Save settings and update/publish it to apply TWIGA business knowledge."
                            } catch (e: Exception) { error = e.message ?: "Could not link existing Retell agent." }
                            finally { loading = false }
                        }
                    },
                    enabled = keyConfigured && existingAgentId.isNotBlank() && !loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Link my existing Retell agent") }
                OutlinedTextField(
                    bridgeNumber, { bridgeNumber = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("AI bridge phone number (E.164)") }, singleLine = true,
                    placeholder = { Text("+1… or +254…") }
                )
                Text("This is the AI provider's phone number that the TWIGA handset calls as a second SIM call, then merges with the customer call. It must be assigned to the selected Retell agent (or the selected Vapi assistant). Carrier conference support and airtime are required.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = {
                        loading = true; error = ""
                        scope.launch {
                            try {
                                val result = TwigaRetellApi.listPhoneNumbers(url, key, session, businessId)
                                val rows = result.optJSONArray("phoneNumbers") ?: JSONArray()
                                retellNumbers = (0 until rows.length()).mapNotNull { index ->
                                    val item = rows.optJSONObject(index) ?: return@mapNotNull null
                                    val number = item.optString("phone_number").ifBlank { item.optString("number") }
                                    if (number.isBlank()) null else number to item.optString("inbound_agent_id")
                                }
                                message = if (retellNumbers.isEmpty()) "No Retell phone numbers were returned. Import or configure a phone number in Retell first." else "Select the Retell number attached to your agent below."
                            } catch (e: Exception) { error = e.message ?: "Could not list Retell phone numbers." }
                            finally { loading = false }
                        }
                    },
                    enabled = keyConfigured && !loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Load my Retell phone numbers") }
                retellNumbers.forEach { (number, assignedAgent) ->
                    AssistChip(
                        onClick = { bridgeNumber = number },
                        label = { Text(number + if (assignedAgent == agentId && agentId.isNotBlank()) " · this agent" else " · agent " + assignedAgent.take(10)) }
                    )
                }
                Text("Saving an empty number clears the SIM-to-AI bridge. Use only a number you control and that is assigned to this business's active AI assistant.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Willa · British · Middle aged", fontWeight = FontWeight.SemiBold)
                Text("Retell-native Willa voice · no Fish Audio account or API key required", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("English (UK) and Kiswahili (Kenya) are always enabled together.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text("English · en-GB") })
                    AssistChip(onClick = {}, label = { Text("Kiswahili · sw-KE") })
                }
                Text("Voice", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = voiceId == "retell-Willa", onClick = { voiceId = "retell-Willa"; voiceModel = "" })
                    Column {
                        Text("Willa · Retell native")
                        Text("Retell handles speech generation directly. No external Fish Audio voice provider is required.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Divider()
                Text("Language model", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = model == "gpt-6-luna", onClick = { model = "gpt-6-luna" })
                    Column {
                        Text("GPT-6 Luna · $0.0032/min (LLM only)")
                        Text("This is the LLM component only. Retell-native speech and telephony are charged under Retell's applicable voice and usage rates; carrier airtime and conference-call charges may also apply.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = model == "gpt-5.6-luna", onClick = { model = "gpt-5.6-luna" })
                    Text("GPT-5.6 Luna · higher LLM cost")
                }
                Divider()
                Text("Background ambience · subtle office/call-centre", style = MaterialTheme.typography.labelLarge)
                Slider(value = ambient, onValueChange = { ambient = it }, valueRange = 0f..0.3f)
                Text("Volume: " + String.format("%.2f", ambient), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Retell offers call-centre ambience rather than a named office preset. TWIGA keeps it very quiet so it does not distract from speech.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Call recording & privacy", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = recordingEnabled, onCheckedChange = { recordingEnabled = it })
                    Column(Modifier.weight(1f)) {
                        Text("Allow call recordings and transcript storage")
                        Text(if (recordingEnabled) "Recording is enabled for this business. Make sure your recording notice and consent process are in place." else "Off by default. TWIGA stores call activity metadata only; it does not retain audio or full transcripts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedTextField(recordingNotice, { recordingNotice = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Customer recording notice") }, minLines = 2, enabled = recordingEnabled)
            }
        }

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Business knowledge & call behaviour", fontWeight = FontWeight.SemiBold)
                Text("The Retell agent is built from this business's profile, product names/prices/stock, services, FAQs, opening hours, policies and instructions. No other business's records are included.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(inboundPrompt, { inboundPrompt = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Incoming call instructions") }, minLines = 3)
                OutlinedTextField(outboundPrompt, { outboundPrompt = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Outgoing call prompt") }, minLines = 4)
                Button(
                    onClick = {
                        loading = true; error = ""; message = ""
                        scope.launch {
                            try {
                                TwigaRetellApi.saveSettings(url, key, session, businessId, provider, voiceId, voiceModel, model, ambient.toDouble(), inboundPrompt, outboundPrompt, recordingEnabled, recordingNotice, bridgeNumber)
                                message = "Voice settings saved for " + businessName + "."
                            } catch (e: Exception) { error = e.message ?: "Could not save settings." }
                            finally { loading = false }
                        }
                    },
                    enabled = configLoaded && !loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save voice settings") }
                Button(
                    onClick = {
                        loading = true; error = ""; message = ""
                        scope.launch {
                            try {
                                TwigaRetellApi.saveSettings(url, key, session, businessId, "retell", voiceId, voiceModel, model, ambient.toDouble(), inboundPrompt, outboundPrompt, recordingEnabled, recordingNotice, bridgeNumber)
                                val result = TwigaRetellApi.provision(url, key, session, businessId)
                                val saved = result.optJSONObject("config")
                                provider = "retell"
                                agentId = saved?.optString("retell_agent_id", "") ?: ""
                                llmId = saved?.optString("retell_llm_id", "") ?: ""
                                model = saved?.optString("retell_model", model) ?: model
                                message = result.optString("message", "Retell agent provisioned.") + " " + result.optString("modelNotice", "")
                            } catch (e: Exception) { error = e.message ?: "Retell agent could not be built." }
                            finally { loading = false }
                        }
                    },
                    enabled = keyConfigured && !loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (agentId.isBlank()) "Build & activate Retell assistant" else "Update & publish Retell assistant") }
                if (agentId.isNotBlank()) {
                    Text("Published Retell agent: " + agentId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text("LLM: " + llmId + " · " + model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Card {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("In-app voice test", fontWeight = FontWeight.SemiBold)
                Text("This is an in-app internet voice test. Cellular SIM AI calls use the Android app's carrier conference bridge and require a working AI bridge number plus carrier conference-call support.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(callStatus, fontWeight = FontWeight.Medium)
                if (accessToken.isBlank()) {
                    Button(onClick = { startCall() }, enabled = provider == "retell" && agentId.isNotBlank() && keyConfigured && !loading, modifier = Modifier.fillMaxWidth()) {
                        Text("Start Willa voice test")
                    }
                } else {
                    Button(onClick = { webView?.evaluateJavascript("window.endTwigaRetellCall && window.endTwigaRetellCall()", null) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("End voice test")
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(180.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                webChromeClient = object : WebChromeClient() {
                                    override fun onPermissionRequest(request: PermissionRequest) {
                                        val audio = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }.toTypedArray()
                                        if (audio.isNotEmpty()) request.grant(audio) else request.deny()
                                    }
                                }
                                addJavascriptInterface(object {
                                    @JavascriptInterface fun onStatus(value: String) {
                                        scope.launch {
                                            callStatus = value.take(160)
                                            if (value == "Call ended") { accessToken = ""; webReady = false }
                                        }
                                    }
                                    @JavascriptInterface fun onError(value: String) {
                                        scope.launch { callStatus = "Call error: " + value.take(160) }
                                    }
                                    @JavascriptInterface fun onReady() {
                                        scope.launch { webReady = true; callStatus = "Ready to connect" }
                                    }
                                }, "TwigaBridge")
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView, pageUrl: String) {
                                        super.onPageFinished(view, pageUrl)
                                        webReady = true
                                    }
                                }
                                loadDataWithBaseURL("https://app.twiga.co.ke", RETELL_WEB_CLIENT_HTML, "text/html", "UTF-8", null)
                                webView = this
                            }
                        },
                        update = { webView = it }
                    )
                }
                if (permissionPending) Text("Allow microphone access to start the voice test.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply { stopLoading(); loadUrl("about:blank"); removeJavascriptInterface("TwigaBridge"); destroy() }
            webView = null
        }
    }
}

private val RETELL_WEB_CLIENT_HTML = """
<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>
body{font-family:system-ui,sans-serif;background:#f4f4f1;color:#171717;margin:0;padding:12px}
#status{font-size:14px;color:#555;margin-bottom:10px}
button{border:0;border-radius:10px;background:#f47721;color:#171717;font-weight:700;padding:12px 18px;margin:4px 6px 4px 0}
#controls{display:none}
</style></head><body><div id="status">Preparing secure voice session…</div>
<div id="controls"><button id="start" type="button">Start Willa call</button><button id="stop" type="button" style="display:none;background:#b3261e;color:white">End call</button></div>
<script type="module">
import { RetellWebClient } from "https://esm.sh/retell-client-js-sdk";
const client = new RetellWebClient();
let pendingToken = "";
const status = (s) => { document.getElementById("status").textContent=s; try{window.TwigaBridge.onStatus(s)}catch(e){} };
const startButton=document.getElementById("start"),stopButton=document.getElementById("stop");
client.on("call_started",()=>{status("Connected · Willa is listening");startButton.style.display="none";stopButton.style.display="inline-block"});
client.on("call_ended",()=>{status("Call ended");startButton.style.display="inline-block";stopButton.style.display="none";pendingToken=""});
client.on("agent_start_talking",()=>status("Willa is speaking"));
client.on("agent_stop_talking",()=>status("Listening"));
client.on("error",(e)=>{ const m=String(e?.message||e||"Voice error"); status("Call error: "+m); try{window.TwigaBridge.onError(m)}catch(x){} });
window.setTwigaRetellToken = (token) => {
  pendingToken=token || "";
  document.getElementById("controls").style.display=pendingToken?"block":"none";
  status(pendingToken?"Ready · tap Start Willa call to connect your microphone":"Waiting for secure voice token");
};
startButton.addEventListener("click",async()=>{
  if(!pendingToken){status("The secure call token is missing. Start the test again.");return}
  try{status("Connecting…");await client.startCall({accessToken:pendingToken})}
  catch(e){const m=String(e?.message||e||"Unable to connect");status("Call error: "+m);try{window.TwigaBridge.onError(m)}catch(x){}}
});
stopButton.addEventListener("click",async()=>{try{await client.stopCall()}catch(e){status("Could not end call")}});
window.endTwigaRetellCall = async () => { try{await client.stopCall();status("Call ended")}catch(e){status("Could not end call")} };
try{window.TwigaBridge.onReady()}catch(e){}
</script></body></html>
""".trimIndent()


@Composable
internal fun TwigaVoiceRouter(
    url: String,
    key: String,
    session: TwigaSession,
    businessId: String,
    businessName: String,
    vapiContent: @Composable () -> Unit
) {
    var provider by remember(businessId) { mutableStateOf("vapi") }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(businessId, session.token) {
        loading = true
        provider = runCatching {
            TwigaRetellApi.getConfig(url, key, session, businessId)
                .optJSONObject("config")?.optString("provider", "vapi") ?: "vapi"
        }.getOrDefault("vapi")
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (provider == "retell") "Active voice: Retell · Willa" else "Active voice: Vapi",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge
                )
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        Box(Modifier.weight(1f)) {
            if (provider == "retell") {
                TwigaRetellWorkspace(url, key, session, businessId, businessName)
            } else {
                vapiContent()
            }
        }
    }
}
