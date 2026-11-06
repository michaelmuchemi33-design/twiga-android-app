package dev.alenajam.opendialer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

private const val TWIGA_APP_HOST = "app.twiga.co.ke"
private const val TWIGA_APP_URL = "https://app.twiga.co.ke/"

/**
 * Hosts the real TWIGA business application inside the Android dialer shell.
 * Authentication, business data, dashboard screens and the web Vapi client remain
 * owned by the live app; the APK never embeds provider secrets.
 */
@Composable
fun TwigaDashboardTab() {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var pendingPermission by remember { mutableStateOf<PermissionRequest?>(null) }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingPermission
        pendingPermission = null
        if (granted && request?.origin?.host == TWIGA_APP_HOST &&
            PermissionRequest.RESOURCE_AUDIO_CAPTURE in (request.resources ?: emptyArray())
        ) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            request?.deny()
        }
    }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                onPause()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            loading = true
                            loadError = false
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) {
                                loading = false
                                loadError = true
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            if (request == null || request.origin?.host != TWIGA_APP_HOST) {
                                request?.deny()
                                return
                            }
                            val needsAudio = PermissionRequest.RESOURCE_AUDIO_CAPTURE in (request.resources ?: emptyArray())
                            if (!needsAudio) {
                                request.deny()
                                return
                            }
                            if (ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                            } else {
                                pendingPermission = request
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }

                        override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                            if (pendingPermission === request) pendingPermission = null
                        }
                    }

                    webView = this
                    loadUrl(TWIGA_APP_URL)
                }
            },
            update = { }
        )

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (loadError) {
            androidx.compose.material3.Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Could not load TWIGA. Check your internet connection.")
                    Button(onClick = {
                        loadError = false
                        loading = true
                        webView?.loadUrl(TWIGA_APP_URL)
                    }) {
                        Text("Try again")
                    }
                }
            }
        }
    }
}
