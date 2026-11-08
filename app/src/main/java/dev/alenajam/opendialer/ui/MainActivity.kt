package dev.alenajam.opendialer.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.alenajam.opendialer.core.common.DefaultPhoneManager
import dev.alenajam.opendialer.feature.appShell.DialerApp
import dev.alenajam.opendialer.remote.RemoteDesk
import dev.alenajam.opendialer.remote.RemoteDeskService
import dev.alenajam.opendialer.update.UpdateManager
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var defaultPhoneManager: DefaultPhoneManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                homeContent = { callbacks -> TwigaWorkspaceHome(callbacks) },
                forceLightTheme = true
            )
        }
    }
}
