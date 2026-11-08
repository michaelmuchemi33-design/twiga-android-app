package dev.alenajam.opendialer.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.alenajam.opendialer.core.common.DefaultPhoneManager
import dev.alenajam.opendialer.remote.RemoteDesk
import dev.alenajam.opendialer.remote.RemoteDeskService
import dev.alenajam.opendialer.update.UpdateManager
import javax.inject.Inject

/**
 * TWIGA's main screen is the actual responsive business workspace.
 * Android's Telecom InCallService remains responsible for cellular call UI.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var defaultPhoneManager: DefaultPhoneManager

    private var webView: WebView? = null
    private var pendingWebPermission: PermissionRequest? = null

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
        requestFullScreenCallAccessIfNeeded()
    }

    private val fullScreenAccessRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        UpdateManager.autoCheck(applicationContext)
        if (RemoteDesk.isPaired(this) && RemoteDesk.remoteControl(this)) {
            runCatching { startForegroundService(Intent(this, RemoteDeskService::class.java)) }
        }

        showWorkspace()
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
            cacheMode = WebSettings.LOAD_DEFAULT
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

        setContentView(view)
        view.loadUrl(WORKSPACE_URL)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = webView
                if (current != null && current.canGoBack()) current.goBack() else finish()
            }
        })
    }

    private fun requestDefaultDialerIfNeeded() {
        val prefs = getSharedPreferences("twiga_setup", MODE_PRIVATE)
        if (!defaultPhoneManager.isDefaultDialer() &&
            !prefs.getBoolean("default_phone_prompted", false)
        ) {
            prefs.edit().putBoolean("default_phone_prompted", true).apply()
            defaultPhoneManager.createRequestDefaultDialerIntent()?.let {
                defaultDialerRequest.launch(it)
            }
        } else {
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
        private const val WORKSPACE_URL = "https://app.twiga.co.ke/#phone"
    }
}
