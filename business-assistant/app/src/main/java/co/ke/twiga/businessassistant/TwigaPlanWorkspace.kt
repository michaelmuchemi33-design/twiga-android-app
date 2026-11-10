package co.ke.twiga.businessassistant

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun TwigaPlanWorkspace() {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(bottom = 20.dp)) {
        Text("Plan & payments", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("One-off payments. No automatic renewal.", fontSize = 13.sp, color = Muted)
        Spacer(Modifier.height(14.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp)) {
                Text("TWIGA Business plan", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("KSh 375", fontWeight = FontWeight.Bold, fontSize = 30.sp, color = Orange)
                Text("per 7 days", color = Muted)
                Divider(Modifier.padding(vertical = 12.dp))
                Text("KSh 1,500 for 4 weeks (28 days)", fontWeight = FontWeight.SemiBold)
                Text("Each payment covers one week. Four weekly payments equal KSh 1,500 over 28 days.", fontSize = 12.sp, color = Muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp)) {
                Text("Three-day settlement grace period", fontWeight = FontWeight.Bold)
                Text("When paid access reaches its due date, TWIGA allows 3 more days to settle the payment. After that, TWIGA cloud workspace features pause until payment is confirmed.", fontSize = 13.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                Text("Your normal phone calls, phone dialler and personal WhatsApp app stay usable. TWIGA does not lock your handset or block normal WhatsApp.", fontSize = 13.sp, color = Muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp)) {
                Text("PayPlus checkout", fontWeight = FontWeight.Bold)
                Text("PayPlus one-off checkout is not connected yet. TWIGA will show the verified payment instructions here once the Kenya merchant account and KES settlement are confirmed. Do not use an unofficial payment link.", fontSize = 12.sp, color = Muted)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/254101122065?text=Hello%20TWIGA%2C%20please%20send%20the%20verified%20one-off%20payment%20instructions."))
                    context.startActivity(intent)
                }, modifier = Modifier.fillMaxWidth()) { Text("WhatsApp TWIGA support • 0101122065") }
            }
        }
    }
}
