package dev.alenajam.opendialer.ui

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF111827)
private val Muted = Color(0xFF667085)
private val Canvas = Color(0xFFF5F6F8)
private val Blue = Color(0xFF2457D6)

private data class LocalCustomer(val name: String, val phone: String, val note: String)
private data class LocalOrder(val customer: String, val details: String, val amount: String, val status: String)
private data class LocalCall(val number: String, val time: Long)

private fun readCustomers(prefs: android.content.SharedPreferences): List<LocalCustomer> = runCatching {
    val array = JSONArray(prefs.getString("customers_v1", "[]"))
    (0 until array.length()).map { i -> array.getJSONObject(i).let { LocalCustomer(it.optString("name"), it.optString("phone"), it.optString("note")) } }
}.getOrDefault(emptyList())

private fun writeCustomers(prefs: android.content.SharedPreferences, rows: List<LocalCustomer>) {
    val array = JSONArray()
    rows.forEach { array.put(JSONObject().put("name", it.name).put("phone", it.phone).put("note", it.note)) }
    prefs.edit().putString("customers_v1", array.toString()).apply()
}

private fun readOrders(prefs: android.content.SharedPreferences): List<LocalOrder> = runCatching {
    val array = JSONArray(prefs.getString("orders_v1", "[]"))
    (0 until array.length()).map { i -> array.getJSONObject(i).let { LocalOrder(it.optString("customer"), it.optString("details"), it.optString("amount"), it.optString("status", "New")) } }
}.getOrDefault(emptyList())

private fun writeOrders(prefs: android.content.SharedPreferences, rows: List<LocalOrder>) {
    val array = JSONArray()
    rows.forEach { array.put(JSONObject().put("customer", it.customer).put("details", it.details).put("amount", it.amount).put("status", it.status)) }
    prefs.edit().putString("orders_v1", array.toString()).apply()
}

private fun readCalls(prefs: android.content.SharedPreferences): List<LocalCall> = runCatching {
    val array = JSONArray(prefs.getString("call_shortcuts_v1", "[]"))
    (0 until array.length()).map { i -> array.getJSONObject(i).let { LocalCall(it.optString("number"), it.optLong("time")) } }
}.getOrDefault(emptyList())

private fun writeCalls(prefs: android.content.SharedPreferences, rows: List<LocalCall>) {
    val array = JSONArray()
    rows.take(50).forEach { array.put(JSONObject().put("number", it.number).put("time", it.time)) }
    prefs.edit().putString("call_shortcuts_v1", array.toString()).apply()
}

class ReceptionistActivity : ComponentActivity() {
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Canvas, surface = Color.White)) {
                ReceptionistScreen(
                    onDefaultDialer = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            val manager = getSystemService(RoleManager::class.java)
                            if (manager.isRoleAvailable(RoleManager.ROLE_DIALER) && !manager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                                roleLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ReceptionistScreen(onDefaultDialer: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("unity_receptionist", Context.MODE_PRIVATE) }
    var business by remember { mutableStateOf(prefs.getString("business", "") ?: "") }
    var greeting by remember { mutableStateOf(prefs.getString("greeting", "Hello! Thanks for calling. How can I help you today?") ?: "") }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "") ?: "") }
    var assistantEnabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var tab by remember { mutableIntStateOf(0) }
    var customers by remember { mutableStateOf(readCustomers(prefs)) }
    var orders by remember { mutableStateOf(readOrders(prefs)) }
    var calls by remember { mutableStateOf(readCalls(prefs)) }
    var phone by remember { mutableStateOf("") }
    var customerName by remember { mutableStateOf("") }
    var customerPhone by remember { mutableStateOf("") }
    var customerNote by remember { mutableStateOf("") }
    var orderCustomer by remember { mutableStateOf("") }
    var orderDetails by remember { mutableStateOf("") }
    var orderAmount by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    fun saveSettings() {
        prefs.edit().putString("business", business).putString("greeting", greeting)
            .putString("endpoint", endpoint).putBoolean("enabled", assistantEnabled).apply()
        message = "Settings saved on this phone."
    }
    fun dial(number: String) {
        val clean = number.trim()
        if (clean.isBlank()) { message = "Enter a phone number first."; return }
        try {
            calls = listOf(LocalCall(clean, System.currentTimeMillis())) + calls
            writeCalls(prefs, calls)
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", clean, null)))
            message = "Phone dialler opened. Review the number and press call."
        } catch (_: Exception) { message = "Could not open the phone dialler on this device." }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                val tabs = listOf("Home" to Icons.Default.Home, "Calls" to Icons.Default.Call, "Orders" to Icons.Default.ShoppingBag, "Customers" to Icons.Default.People, "Setup" to Icons.Default.Settings)
                tabs.forEachIndexed { index, item ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index; message = "" }, icon = { Icon(item.second, null) }, label = { Text(item.first, maxLines = 1, fontSize = 10.sp) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().background(Canvas).padding(padding)) {
            Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, null, tint = Ink, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("UNITY", fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = Ink)
                    Text("AI RECEPTIONIST", fontSize = 11.sp, color = Muted)
                }
                Text("LOCAL MODE", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (message.isNotBlank()) {
                Surface(color = Color(0xFFEAF0FF), modifier = Modifier.fillMaxWidth()) {
                    Text(message, Modifier.padding(horizontal = 18.dp, vertical = 9.dp), color = Ink, fontSize = 12.sp)
                }
            }
            when (tab) {
                0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Text(if (business.isBlank()) "Your business front desk" else business, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text("Call customers, record orders and keep customer details on this phone.", color = Muted)
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("RECEPTIONIST CONNECTION", color = Color.LightGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(if (endpoint.startsWith("https://")) "Backend URL saved" else "AI calling not connected", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("The app can manage local customers and orders now. Live AI phone answering requires a configured voice backend and supported internet calling service.", color = Color(0xFFD1D5DB), fontSize = 12.sp)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = assistantEnabled, onCheckedChange = { assistantEnabled = it; saveSettings() })
                                    Text(if (assistantEnabled) "AI setting enabled (connection not verified)" else "AI setting off", color = Color.White, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            StatCard("Customers", customers.size.toString(), Modifier.weight(1f))
                            StatCard("Orders", orders.size.toString(), Modifier.weight(1f))
                            StatCard("Call attempts", calls.size.toString(), Modifier.weight(1f))
                        }
                    }
                    item {
                        OutlinedTextField(phone, { phone = it }, label = { Text("Phone number to call") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                        Button(onClick = { dial(phone) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp)); Text("Call using phone SIM") }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { tab = 2 }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.AddShoppingCart, null); Text(" New order") }
                            OutlinedButton(onClick = { tab = 3 }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PersonAdd, null); Text(" Customer") }
                        }
                    }
                    item {
                        OutlinedButton(onClick = onDefaultDialer, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PhoneAndroid, null); Spacer(Modifier.width(8.dp)); Text("Set as default phone app") }
                    }
                    item { Text("SIM calls open the Android phone dialler for you to confirm. This APK does not secretly answer or capture SIM-call audio.", color = Muted, fontSize = 12.sp) }
                }
                1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Make a call", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink); Text("Start a normal call through your phone's SIM.", color = Muted) }
                    item {
                        OutlinedTextField(phone, { phone = it }, label = { Text("Phone number") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                        Button(onClick = { dial(phone) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp)); Text("Open phone call") }
                    }
                    item { Text("Recent dialler launches", fontWeight = FontWeight.Bold, color = Ink) }
                    if (calls.isEmpty()) item { EmptyCard("No calls started from this screen yet.") }
                    items(calls) { call ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CallMade, tint = Blue, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) { Text(call.number, fontWeight = FontWeight.SemiBold); Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(call.time)), color = Muted, fontSize = 11.sp) }
                                IconButton(onClick = { dial(call.number) }) { Icon(Icons.Default.Call, contentDescription = "Call again") }
                            }
                        }
                    }
                }
                2 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Orders", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink); Text("Create and track customer orders stored on this device.", color = Muted) }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                OutlinedTextField(orderCustomer, { orderCustomer = it }, label = { Text("Customer name or phone") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(orderDetails, { orderDetails = it }, label = { Text("Items / service / order details") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                                OutlinedTextField(orderAmount, { orderAmount = it }, label = { Text("Amount (KSh, optional)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                Button(onClick = {
                                    if (orderCustomer.isBlank() || orderDetails.isBlank()) message = "Add the customer and order details."
                                    else {
                                        orders = listOf(LocalOrder(orderCustomer.trim(), orderDetails.trim(), orderAmount.trim(), "New")) + orders
                                        writeOrders(prefs, orders)
                                        orderCustomer = ""; orderDetails = ""; orderAmount = ""
                                        message = "Order saved on this phone."
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Save order") }
                            }
                        }
                    }
                    if (orders.isEmpty()) item { EmptyCard("No orders yet. Create your first order above.") }
                    items(orders) { order ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) { Text(order.customer, Modifier.weight(1f), fontWeight = FontWeight.Bold); AssistChip(onClick = {}, label = { Text(order.status) }) }
                                Text(order.details, color = Ink)
                                if (order.amount.isNotBlank()) Text("KSh ${order.amount}", color = Muted, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { orders = orders.map { if (it == order) it.copy(status = "Processing") else it }; writeOrders(prefs, orders); message = "Order marked processing." }) { Text("Processing") }
                                    TextButton(onClick = { orders = orders.map { if (it == order) it.copy(status = "Completed") else it }; writeOrders(prefs, orders); message = "Order completed." }) { Text("Complete") }
                                    TextButton(onClick = { orders = orders - order; writeOrders(prefs, orders); message = "Order deleted." }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
                3 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Customers", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink); Text("Save customer contact details and notes.", color = Muted) }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                OutlinedTextField(customerName, { customerName = it }, label = { Text("Customer name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                OutlinedTextField(customerPhone, { customerPhone = it }, label = { Text("Phone number") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                                OutlinedTextField(customerNote, { customerNote = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                                Button(onClick = {
                                    if (customerName.isBlank() || customerPhone.isBlank()) message = "Enter the customer's name and phone number."
                                    else {
                                        customers = listOf(LocalCustomer(customerName.trim(), customerPhone.trim(), customerNote.trim())) + customers
                                        writeCustomers(prefs, customers)
                                        customerName = ""; customerPhone = ""; customerNote = ""
                                        message = "Customer saved on this phone."
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("Save customer") }
                            }
                        }
                    }
                    if (customers.isEmpty()) item { EmptyCard("No customers yet. Add a customer above.") }
                    items(customers) { customer ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(customer.name, fontWeight = FontWeight.Bold, color = Ink)
                                    Text(customer.phone, color = Muted, fontSize = 12.sp)
                                    if (customer.note.isNotBlank()) Text(customer.note, color = Muted, fontSize = 12.sp)
                                }
                                IconButton(onClick = { dial(customer.phone) }) { Icon(Icons.Default.Call, contentDescription = "Call customer") }
                                IconButton(onClick = { customers = customers - customer; writeCustomers(prefs, customers); message = "Customer deleted." }) { Icon(Icons.Default.Delete, contentDescription = "Delete customer") }
                            }
                        }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Business setup", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink); Text("These settings are saved locally on this phone.", color = Muted) }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(business, { business = it }, label = { Text("Business name") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(greeting, { greeting = it }, label = { Text("Receptionist greeting") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("AI backend HTTPS URL") }, modifier = Modifier.fillMaxWidth())
                                Button(onClick = { saveSettings() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Save settings") }
                            }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Calling status", fontWeight = FontWeight.Bold)
                                Text("SIM calling: opens Android's dialler for manual confirmation.", color = Muted, fontSize = 12.sp)
                                Text("AI answering: not connected until a compatible SIP/WebRTC voice backend is configured.", color = Muted, fontSize = 12.sp)
                                Text("WhatsApp and M-Pesa: not connected in this build.", color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp)) { Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink); Text(label, color = Muted, fontSize = 11.sp) }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Text(text, Modifier.fillMaxWidth().padding(18.dp), color = Muted, fontSize = 13.sp)
    }
}
