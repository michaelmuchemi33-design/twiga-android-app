package dev.alenajam.opendialer.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import dagger.hilt.android.AndroidEntryPoint
import dev.alenajam.opendialer.core.common.DefaultPhoneManager
import dev.alenajam.opendialer.feature.appShell.DialerApp
import dev.alenajam.opendialer.feature.appShell.HomeNavigationItem
import dev.alenajam.opendialer.feature.appShell.HomeScreenConfiguration
import dev.alenajam.opendialer.remote.RemoteDesk
import dev.alenajam.opendialer.remote.RemoteDeskService
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
        if (RemoteDesk.isPaired(this)) {
            runCatching { startForegroundService(Intent(this, RemoteDeskService::class.java)) }
        }
        setContent {
            DialerApp(
                defaultPhoneManager = defaultPhoneManager,
                homeScreenConfiguration = HomeScreenConfiguration(
                    topBarAction = { AiToggleAction() },
                    customNavigationItem = HomeNavigationItem(
                        label = { Text("Business") },
                        icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
                        content = { BusinessTab() },
                    ),
                ),
            )
        }
    }
}
