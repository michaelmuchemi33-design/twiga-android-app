package dev.alenajam.opendialer.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.alenajam.opendialer.core.common.DefaultPhoneManager
import dev.alenajam.opendialer.remote.RemoteDesk
import dev.alenajam.opendialer.remote.RemoteDeskService
import dev.alenajam.opendialer.update.UpdateManager
import javax.inject.Inject
import org.json.JSONObject

/**
 * TWIGA's main screen is the actual responsive business workspace.
 * Android's Telecom InCallService remains responsible for cellular call UI.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var defaultPhoneManager: DefaultPhoneManager

    private var webView: WebView? = null
    private var pendingWebPermission: PermissionRequest? = null
    @Volatile private var googleOAuthInProgress = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingWebPermission
        pendingWebPermission = null
        if (request != null) {
            if (granted && request.origin.scheme == "https" &&
                request.origin.host == WORKSPACE_HOST &&
                request.resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
            ) {
                request.grant(request.resources)
            } else {
                request.deny()
            }
        }
    }

    private val defaultDialerRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (defaultPhoneManager.isDefaultDialer() && RemoteDesk.isPaired(this)) {
            requestPhonePermissions()
        } else {
            requestFullScreenCallAccessIfNeeded()
        }
    }

    private val fullScreenAccessRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    private val phonePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val required = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        val granted = required.all { permission ->
            results[permission] == true || ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        webView?.evaluateJavascript("window.twigaNativePermissionsResult && window.twigaNativePermissionsResult($granted)", null)
        if (granted && RemoteDesk.isPaired(this)) {
            startRemoteDesk()
            requestBatteryOptimizationExemption()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        UpdateManager.autoCheck(applicationContext)

        // Start the paired-phone bridge only after the app has the dialler role
        // and the runtime phone permissions. Starting it before that can make
        // remote answer/dial commands fail silently on a fresh install.

        showWorkspace()
        handleOAuthCallback(intent)
        requestDefaultDialerIfNeeded()
    }

    private fun showWorkspace() {
        val view = WebView(this)
        webView = view
        view.setBackgroundColor(android.graphics.Color.WHITE)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val host = uri.host.orEmpty().lowercase()
                if (googleOAuthInProgress && (host == "accounts.google.com" || host == "oauth2.googleapis.com")) {
                    return runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    }.getOrDefault(true)
                }
                if (host == WORKSPACE_HOST || host == SUPABASE_HOST ||
                    host == "accounts.google.com" || host.endsWith(".google.com") ||
                    host.endsWith(".gstatic.com") || host.endsWith(".googleusercontent.com")
                ) return false

                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                }.getOrDefault(true)
            }
        }

        view.addJavascriptInterface(TwigaAndroidBridge(), "TwigaAndroid")

        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val trusted = request.origin.scheme == "https" &&
                    request.origin.host == WORKSPACE_HOST
                val audioOnly = request.resources.isNotEmpty() &&
                    request.resources.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                if (!trusted || !audioOnly) {
                    request.deny()
                    return
                }
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    request.grant(request.resources)
                } else {
                    pendingWebPermission?.deny()
                    pendingWebPermission = request
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingWebPermission == request) pendingWebPermission = null
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(view)
        view.loadUrl(WORKSPACE_URL)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = webView
                if (current != null && current.canGoBack()) current.goBack() else finish()
            }
        })
    }

    private inner class TwigaAndroidBridge {
        @JavascriptInterface
        fun isPaired(): Boolean = RemoteDesk.isPaired(this@MainActivity)

        @JavascriptInterface
        fun beginGoogleOAuth() { googleOAuthInProgress = true }

        @JavascriptInterface
        fun cancelGoogleOAuth() { googleOAuthInProgress = false }

        @JavascriptInterface
        fun requestPhoneSetup() {
            runOnUiThread {
                if (!defaultPhoneManager.isDefaultDialer()) requestDefaultDialerManually()
                else requestPhonePermissions()
            }
        }

        @JavascriptInterface
        fun claimPairingCode(code: String) {
            val clean = code.filter { it.isDigit() }.take(6)
            if (clean.length != 6 || RemoteDesk.isPaired(this@MainActivity)) return
            Thread {
                var success = false
                var message = "Could not pair this phone."
                try {
                    val response = JSONObject(
                        RemoteDesk.rpc(
                            "device_claim",
                            JSONObject().put("p_code", clean).put("p_name", Build.MODEL ?: "Android phone")
                        )
                    )
                    if (response.has("error")) throw IllegalStateException(response.optString("error"))
                    val token = response.optString("token")
                    if (token.isBlank()) throw IllegalStateException("The pairing service did not return a device token.")
                    RemoteDesk.save(this@MainActivity, token, response.optString("business"))
                    success = true
                    message = "Phone connected. Enabling call permissions…"
                } catch (error: Exception) {
                    message = error.message ?: "Pairing failed. Check your connection and try again."
                }
                val ok = success
                val resultMessage = message
                mainHandler.post {
                    val js = "window.twigaNativePairResult && window.twigaNativePairResult($ok, ${JSONObject.quote(resultMessage)})"
                    webView?.evaluateJavascript(js, null)
                    if (ok) {
                        // requestPhonePermissions starts the remote bridge only
                        // after the required phone permissions are confirmed.
                        requestPhonePermissions()
                    }
                }
            }.start()
        }
    }

    private fun requestPhonePermissions() {
        val required = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startRemoteDesk()
            requestBatteryOptimizationExemption()
        } else {
            phonePermissions.launch(missing.toTypedArray())
        }
    }

    private fun startRemoteDesk() {
        if (!RemoteDesk.isPaired(this) || !RemoteDesk.remoteControl(this)) return
        runCatching { startForegroundService(Intent(this, RemoteDeskService::class.java)) }
    }

    private fun requestDefaultDialerManually() {
        if (defaultPhoneManager.isDefaultDialer()) {
            requestPhonePermissions()
            requestBatteryOptimizationExemption()
            return
        }
        defaultPhoneManager.createRequestDefaultDialerIntent()?.let { defaultDialerRequest.launch(it) }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !RemoteDesk.isPaired(this)) return
        val power = getSystemService(android.os.PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    private fun handleOAuthCallback(source: Intent?) {
        val uri = source?.data ?: return
        if (uri.scheme != "twiga" || uri.host != "auth" || uri.path != "/callback") return
        googleOAuthInProgress = false
        val callback = Uri.Builder()
            .scheme("https")
            .authority(WORKSPACE_HOST)
            .path("/")
            .encodedQuery(uri.encodedQuery)
            .encodedFragment(uri.encodedFragment)
            .build()
        webView?.loadUrl(callback.toString())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthCallback(intent)
    }

    private fun requestDefaultDialerIfNeeded() {
        val prefs = getSharedPreferences("twiga_setup", MODE_PRIVATE)
        if (!defaultPhoneManager.isDefaultDialer()) {
            if (!prefs.getBoolean("default_phone_prompted", false)) {
                prefs.edit().putBoolean("default_phone_prompted", true).apply()
                defaultPhoneManager.createRequestDefaultDialerIntent()?.let {
                    defaultDialerRequest.launch(it)
                }
            } else {
                // The owner can retry from the in-app Phone setup action.
                requestFullScreenCallAccessIfNeeded()
            }
            return
        }

        // A returning user may already have granted the dialler role before
        // pairing. Always re-check runtime permissions and restart the bridge.
        if (RemoteDesk.isPaired(this)) requestPhonePermissions()
        requestFullScreenCallAccessIfNeeded()
    }

    private fun requestFullScreenCallAccessIfNeeded() {
        if (Build.VERSION.SDK_INT < 34 || !defaultPhoneManager.isDefaultDialer()) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.canUseFullScreenIntent()) return
        val prefs = getSharedPreferences("twiga_setup", MODE_PRIVATE)
        if (prefs.getBoolean("full_screen_prompted", false)) return
        prefs.edit().putBoolean("full_screen_prompted", true).apply()
        fullScreenAccessRequest.launch(
            Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        )
    }

    override fun onResume() {
        super.onResume()
        // Re-check on foreground return; UpdateManager throttles network checks to once per six hours.
        UpdateManager.autoCheck(applicationContext)
    }

    override fun onDestroy() {
        pendingWebPermission?.deny()
        pendingWebPermission = null
        webView?.apply {
            stopLoading()
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val WORKSPACE_HOST = "app.twiga.co.ke"
        private const val SUPABASE_HOST = "ztyxxikkposfpxsutnfr.supabase.co"
        private const val WORKSPACE_URL = "https://app.twiga.co.ke/"
    }
}
