package dev.alenajam.opendialer.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.app.AlertDialog
import android.telecom.TelecomManager
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
    private var pendingDialNumber: String? = null
    private var phonePermissionRequestInFlight = false
    private var pendingWebPermission: PermissionRequest? = null
    @Volatile private var googleOAuthInProgress = false
    private val deviceRegistrationInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val deviceRegistrationPolling = java.util.concurrent.atomic.AtomicBoolean(false)
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
        phonePermissionRequestInFlight = false
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
        if (granted) {
            if (RemoteDesk.isPaired(this)) {
                startRemoteDesk()
                requestBatteryOptimizationExemption()
            }
            val pending = pendingDialNumber
            if (!pending.isNullOrBlank()) {
                pendingDialNumber = null
                placeSimCall(pending)
            }
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

        // The user signs in first. The shared workspace then registers this
        // handset; only after that do we request the Android phone role/permissions.
        showWorkspace()
        handleOAuthCallback(intent)
        resumePhoneAccess()
        handleDialIntent(intent)
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

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val host = runCatching { Uri.parse(url).host }.getOrNull()
                if (host == WORKSPACE_HOST) attemptAutomaticDeviceRegistration()
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

    /**
     * The installed production dashboard may be older than the auto-registering
     * branch. Read the signed-in session from its same-origin WebView, register
     * once, and observe sign-out so the device token is cleared with the account.
     */
    private fun attemptAutomaticDeviceRegistration(attempt: Int = 0) {
        if (RemoteDesk.isPaired(this)) {
            deviceRegistrationPolling.set(false)
            return
        }
        if (attempt == 0 && !deviceRegistrationPolling.compareAndSet(false, true)) return
        val script = """
            (function() {
              if (!window.TwigaAndroid) return false;
              if (!window.__twigaNativeAuthObserverInstalled &&
                  window.sb && window.sb.auth && window.sb.auth.onAuthStateChange) {
                window.__twigaNativeAuthObserverInstalled = true;
                window.sb.auth.onAuthStateChange(function(event, session) {
                  if (event === "SIGNED_OUT") {
                    window.TwigaAndroid.clearDeviceRegistration();
                  } else if (event === "SIGNED_IN" && session) {
                    var tries = 0;
                    var connect = function() {
                      var business = window.BIZ;
                      if (business && business.id) {
                        window.TwigaAndroid.registerDevice(session.access_token, business.id);
                      } else if (tries++ < 20) {
                        window.setTimeout(connect, 500);
                      }
                    };
                    connect();
                  }
                });
              }
              if (window.TwigaAndroid.isPaired()) return true;
              var session = window.SESS;
              var business = window.BIZ;
              if (!session || !session.access_token || !business || !business.id) return false;
              window.TwigaAndroid.registerDevice(session.access_token, business.id);
              return true;
            })()
        """.trimIndent()
        webView?.evaluateJavascript(script) { result ->
            if (result != "true" && !RemoteDesk.isPaired(this) && attempt < 40) {
                mainHandler.postDelayed({ attemptAutomaticDeviceRegistration(attempt + 1) }, 700L)
            } else {
                deviceRegistrationPolling.set(false)
            }
        }
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
        fun registerDevice(accessToken: String, businessId: String) {
            val jwt = accessToken.trim()
            val business = businessId.trim()
            if (jwt.isBlank() || !business.matches(Regex("^[0-9a-fA-F-]{36}$"))) return
            if (RemoteDesk.isPaired(this@MainActivity) || !deviceRegistrationInFlight.compareAndSet(false, true)) return

            Thread {
                var success = false
                var message = "Could not connect this phone to TWIGA."
                try {
                    val response = JSONObject(
                        RemoteDesk.rpcAuthenticated(
                            "device_register",
                            JSONObject()
                                .put("p_business_id", business)
                                .put("p_name", (Build.MANUFACTURER + " " + Build.MODEL).trim()),
                            jwt
                        )
                    )
                    val token = response.optString("token")
                    if (token.isBlank()) throw IllegalStateException("The device registration service did not return a token.")
                    RemoteDesk.save(this@MainActivity, token, response.optString("business"))
                    success = true
                    message = "This phone is securely connected to " + response.optString("business", "your TWIGA business") + "."
                } catch (error: Exception) {
                    message = error.message ?: "Device registration failed. Check your connection and sign in again."
                } finally {
                    deviceRegistrationInFlight.set(false)
                }

                val ok = success
                val resultMessage = message
                mainHandler.post {
                    val js = "window.twigaNativeDeviceResult && window.twigaNativeDeviceResult(" + ok + ", " + JSONObject.quote(resultMessage) + ")"
                    webView?.evaluateJavascript(js, null)
                    if (ok) {
                        if (!defaultPhoneManager.isDefaultDialer()) {
                            requestDefaultDialerManually()
                        } else {
                            requestPhonePermissions()
                        }
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun clearDeviceRegistration() {
            runOnUiThread {
                stopService(Intent(this@MainActivity, RemoteDeskService::class.java))
                RemoteDesk.unpair(this@MainActivity)
            }
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
            if (RemoteDesk.isPaired(this)) {
                startRemoteDesk()
                requestBatteryOptimizationExemption()
            }
            val pending = pendingDialNumber
            if (!pending.isNullOrBlank()) {
                pendingDialNumber = null
                placeSimCall(pending)
            }
        } else if (!phonePermissionRequestInFlight) {
            phonePermissionRequestInFlight = true
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
        handleDialIntent(intent)
    }

    /**
     * This app is registered as the system dialler. ACTION_DIAL must therefore
     * do something useful instead of merely opening the business WebView.
     * Confirm the number before placing a normal cellular call on the SIM.
     */
    private fun handleDialIntent(source: Intent?) {
        val action = source?.action ?: return
        if (action != Intent.ACTION_DIAL && action != Intent.ACTION_VIEW) return
        val uri = source.data ?: return
        if (uri.scheme != "tel") return
        val number = uri.schemeSpecificPart.orEmpty().trim().take(40)
        if (number.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle("Call using your SIM?")
            .setMessage("Place a normal phone call to $number?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Call") { _, _ -> placeSimCall(number) }
            .show()
    }

    private fun placeSimCall(number: String) {
        if (!defaultPhoneManager.isDefaultDialer()) {
            pendingDialNumber = number
            requestDefaultDialerManually()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingDialNumber = number
            requestPhonePermissions()
            return
        }
        runCatching {
            getSystemService(TelecomManager::class.java)
                .placeCall(Uri.fromParts("tel", number, null), Bundle())
        }.onFailure { error ->
            AlertDialog.Builder(this)
                .setTitle("Call could not be started")
                .setMessage(error.message ?: "Check phone permissions, SIM availability and carrier service.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun resumePhoneAccess() {
        // Never interrupt the sign-in screen with a phone-role prompt. Returning
        // devices resume automatically when their Android role is already granted.
        if (RemoteDesk.isPaired(this) && defaultPhoneManager.isDefaultDialer()) {
            requestPhonePermissions()
            requestFullScreenCallAccessIfNeeded()
        }
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
