package dev.alenajam.opendialer.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.alenajam.opendialer.remote.RemoteDesk
import org.json.JSONObject

/**
 * Lets the owner talk to their AI receptionist inside the app, the way a customer would.
 * It opens the TWIGA AI page, which uses the microphone and the internet. It does not touch SIM calls.
 */
class AiCallActivity : ComponentActivity() {
    private var web: WebView? = null
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { load() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frame = FrameLayout(this)
        setContentView(frame)
        ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val sp = getSharedPreferences("twiga_ai", MODE_PRIVATE)
                val o = JSONObject().put("prompt", sp.getString("about", "")).put("faq", sp.getString("faq", ""))
                    .put("rules", sp.getString("rules", "")).put("greeting", sp.getString("greeting", ""))
                view.evaluateJavascript("window.TWIGA_LOCAL=" + o.toString() + ";", null)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (request.origin.host == "app.twiga.co.ke") request.grant(request.resources) else request.deny()
                }
            }
        }
        frame.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = w
        onBackPressedDispatcher.addCallback(this) { if (w.canGoBack()) w.goBack() else finish() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) load()
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun load() {
        val t = Uri.encode(RemoteDesk.token(this))
        val saved = getSharedPreferences("twiga_ai", MODE_PRIVATE).getString("name", "").orEmpty()
        val n = Uri.encode(saved.ifBlank { RemoteDesk.business(this).ifBlank { "my business" } })
        web?.loadUrl("https://app.twiga.co.ke/ai#t=$t&n=$n")
    }

    override fun onDestroy() {
        web?.destroy()
        super.onDestroy()
    }
}
