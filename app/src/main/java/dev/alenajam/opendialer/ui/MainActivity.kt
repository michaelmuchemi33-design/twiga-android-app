package dev.alenajam.opendialer.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import dev.alenajam.opendialer.core.common.DefaultPhoneManager
import dev.alenajam.opendialer.feature.appShell.DialerApp
import dev.alenajam.opendialer.feature.appShell.HomeNavigationItem
import dev.alenajam.opendialer.feature.appShell.HomeScreenConfiguration
import dev.alenajam.opendialer.remote.RemoteDesk
import dev.alenajam.opendialer.remote.RemoteDeskService
import dev.alenajam.opendialer.update.UpdateManager
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var defaultPhoneManager: DefaultPhoneManager

    private var googleAuthCallback by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        googleAuthCallback = intent?.data?.takeIf(::isTwigaGoogleCallback)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        UpdateManager.autoCheck(applicationContext)
        if (RemoteDesk.isPaired(this) && RemoteDesk.remoteControl(this)) {
            runCatching { startForegroundService(Intent(this, RemoteDeskService::class.java)) }
        }
        setContent {
            DialerApp(
                defaultPhoneManager = defaultPhoneManager,
                forceLightTheme = true,
                homeScreenConfiguration = HomeScreenConfiguration(
                    topBarAction = { AiToggleAction() },
                    customNavigationItem = HomeNavigationItem(
                        label = { Text("TWIGA") },
                        icon = { selected ->
                            Icon(
                                Icons.Filled.Dashboard,
                                contentDescription = if (selected) "TWIGA dashboard" else null
                            )
                        },
                        content = {
                            BusinessTab(
                                authCallback = googleAuthCallback,
                                onAuthCallbackConsumed = { googleAuthCallback = null }
                            )
                        },
                    ),
                ),
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        googleAuthCallback = intent.data?.takeIf(::isTwigaGoogleCallback)
    }

    private fun isTwigaGoogleCallback(uri: Uri): Boolean =
        uri.scheme == "twiga" &&
            uri.host == "auth" &&
            uri.path == "/callback" &&
            !uri.getQueryParameter("code").isNullOrBlank()
}
