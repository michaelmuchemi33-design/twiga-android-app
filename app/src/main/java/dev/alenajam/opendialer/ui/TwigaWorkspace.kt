package dev.alenajam.opendialer.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.alenajam.opendialer.feature.appShell.HomeScreenCallbacks
import dev.alenajam.opendialer.feature.calls.CallsScreen

private val TwigaOrange = Color(0xFFF47721)
private val TwigaInk = Color(0xFF111111)
private val TwigaMuted = Color(0xFF6B6863)
private val TwigaCanvas = Color(0xFFF6F6F5)

@Composable
fun TwigaWorkspaceHome(callbacks: HomeScreenCallbacks) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current

    MaterialTheme(colorScheme = lightColorScheme(
        primary = TwigaOrange,
        onPrimary = TwigaInk,
        background = Color.White,
        surface = Color.White,
        onSurface = TwigaInk,
        onSurfaceVariant = TwigaMuted
    )) {
        Scaffold(
            containerColor = Color.White,
            topBar = {
                Row(
                    Modifier.fillMaxWidth().background(Color.White).statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(color = TwigaOrange, shape = RoundedCornerShape(11.dp), modifier = Modifier.size(38.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("T", fontWeight = FontWeight.Black, fontSize = 23.sp, color = TwigaInk)
                        }
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("TWIGA", fontWeight = FontWeight.Black, letterSpacing = 2.sp, fontSize = 18.sp, color = TwigaInk)
                        Text("YOUR BUSINESS, IN ONE PLACE", fontSize = 9.sp, letterSpacing = 1.sp, color = TwigaMuted)
                    }
                    Text("●", color = TwigaOrange, fontSize = 12.sp)
                    Spacer(Modifier.width(5.dp))
                    Text("Workspace", fontSize = 11.sp, color = TwigaMuted)
                }
            },
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    val items = listOf(
                        Triple("Home", Icons.Default.Home, 0),
                        Triple("Calls", Icons.Default.Call, 1),
                        Triple("AI", Icons.Default.RecordVoiceOver, 2),
                        Triple("POS", Icons.Default.ShoppingCart, 3),
                        Triple("More", Icons.Default.Menu, 4)
                    )
                    items.forEach { item ->
                        NavigationBarItem(
                            selected = page == item.third,
                            onClick = { page = item.third },
                            icon = { Icon(item.second, contentDescription = null) },
                            label = { Text(item.first, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = TwigaInk,
                                selectedTextColor = TwigaInk,
                                indicatorColor = Color(0xFFFEEFE3),
                                unselectedIconColor = TwigaMuted,
                                unselectedTextColor = TwigaMuted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            when (page) {
                0 -> TwigaOverview(
                    modifier = Modifier.padding(padding),
                    onTalk = { context.startActivity(Intent(context, AiCallActivity::class.java)) },
                    onDial = { callbacks.onOpenDialpad("") },
                    onPos = { page = 3 }
                )
                1 -> CallsScreen(
                    onOpenHistory = callbacks.onOpenHistory,
                    onOpenContacts = { callbacks.onOpenSettings() },
                    onAddFavorite = callbacks.onAddFavorite,
                    onEditNumberBeforeCall = callbacks.onOpenDialpad
                )
                2 -> Column(
                    Modifier.fillMaxSize().background(TwigaCanvas).padding(padding)
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Your AI receptionist", fontSize = 25.sp, fontWeight = FontWeight.Black, color = TwigaInk)
                    Text("Set up how TWIGA speaks to customers, then test the conversation.", color = TwigaMuted, fontSize = 13.sp)
                    AiSettingsCard()
                    AiTalkCard()
                }
                3 -> TwigaPosScreen(onBack = { page = 0 })
                else -> Column(
                    Modifier.fillMaxSize().background(TwigaCanvas).padding(padding)
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("More from TWIGA", fontSize = 25.sp, fontWeight = FontWeight.Black)
                    TwigaActionCard("Open dialler", "Make a call using your phone's SIM.") { callbacks.onOpenDialpad("") }
                    TwigaActionCard("Phone settings", "Manage your default dialler and app settings.") { callbacks.onOpenSettings() }
                    TwigaActionCard("Settings", "Manage phone and app settings.") { callbacks.onOpenSettings() }
                    TwigaActionCard("About TWIGA", "App information and support.") { callbacks.onOpenAbout() }
                    TwigaActionCard("Voicemail", "Review voicemail on this phone.") { callbacks.onOpenVoicemail() }
                }
            }
        }
    }
}

@Composable
private fun TwigaOverview(modifier: Modifier, onTalk: () -> Unit, onDial: () -> Unit, onPos: () -> Unit) {
    Column(
        modifier.fillMaxSize().background(TwigaCanvas).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Your business, connected.", fontSize = 26.sp, fontWeight = FontWeight.Black, color = TwigaInk)
        Text("Calls, customer conversations and sales in one simple workspace.", color = TwigaMuted, fontSize = 13.sp)
        Card(colors = CardDefaults.cardColors(containerColor = TwigaInk), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("AI RECEPTIONIST", color = Color(0xFFFFC18E), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("A customer calls. TWIGA helps.", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text("Test your receptionist, review its setup and keep the customer experience consistent.", color = Color(0xFFE2E0DC), fontSize = 12.sp)
                Button(onClick = onTalk, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = TwigaOrange, contentColor = TwigaInk)) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Talk with AI", fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TwigaQuickAction("Make a call", "Use SIM", Modifier.weight(1f), onDial)
            TwigaQuickAction("Open POS", "Sales & stock", Modifier.weight(1f), onPos)
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("One workspace", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Your dialler, AI receptionist, customer records and point of sale now share the same TWIGA navigation and visual style.", color = TwigaMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun TwigaQuickAction(title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = TwigaInk)
            Text(subtitle, fontSize = 11.sp, color = TwigaMuted)
        }
    }
}

@Composable
private fun TwigaActionCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 12.sp, color = TwigaMuted)
        }
    }
}
