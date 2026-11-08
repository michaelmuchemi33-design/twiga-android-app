package dev.alenajam.opendialer.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val PosInk = Color(0xFF111111)
private val PosOrange = Color(0xFFF47721)
private val PosMuted = Color(0xFF667085)
private val PosBg = Color(0xFFF6F6F5)

private data class PosProduct(val id: String, val name: String, val price: Long, val stock: Int)
private data class PosCartLine(val productId: String, val quantity: Int)
private data class PosSale(val id: String, val at: Long, val total: Long, val payment: String, val summary: String)

private fun loadProducts(p: android.content.SharedPreferences): List<PosProduct> = runCatching {
    val a = JSONArray(p.getString("pos_products_v1", "[]"))
    (0 until a.length()).map { i -> a.getJSONObject(i).let {
        PosProduct(it.optString("id"), it.optString("name"), it.optLong("price"), it.optInt("stock"))
    } }
}.getOrDefault(emptyList())

private fun saveProducts(p: android.content.SharedPreferences, rows: List<PosProduct>) {
    val a = JSONArray()
    rows.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("price", it.price).put("stock", it.stock)) }
    p.edit().putString("pos_products_v1", a.toString()).apply()
}

private fun loadSales(p: android.content.SharedPreferences): List<PosSale> = runCatching {
    val a = JSONArray(p.getString("pos_sales_v1", "[]"))
    (0 until a.length()).map { i -> a.getJSONObject(i).let {
        PosSale(it.optString("id"), it.optLong("at"), it.optLong("total"), it.optString("payment"), it.optString("summary"))
    } }
}.getOrDefault(emptyList())

private fun saveSales(p: android.content.SharedPreferences, rows: List<PosSale>) {
    val a = JSONArray()
    rows.take(100).forEach { a.put(JSONObject().put("id", it.id).put("at", it.at).put("total", it.total).put("payment", it.payment).put("summary", it.summary)) }
    p.edit().putString("pos_sales_v1", a.toString()).apply()
}

class PosActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = PosOrange, onPrimary = PosInk, background = PosBg, surface = Color.White)) {
                TwigaPosScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwigaPosScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("twiga_pos", Context.MODE_PRIVATE) }
    var products by remember { mutableStateOf(loadProducts(prefs)) }
    var sales by remember { mutableStateOf(loadSales(prefs)) }
    var cart by remember { mutableStateOf<List<PosCartLine>>(emptyList()) }
    var payment by remember { mutableStateOf("Cash") }
    var message by remember { mutableStateOf("Sales and products are saved on this phone. Keep a backup before changing devices.") }
    var showAdd by remember { mutableStateOf(false) }
    var itemName by remember { mutableStateOf("") }
    var itemPrice by remember { mutableStateOf("") }
    var itemStock by remember { mutableStateOf("20") }

    val total = cart.sumOf { line ->
        val p = products.firstOrNull { it.id == line.productId }
        (p?.price ?: 0L) * line.quantity
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("TWIGA POS", fontWeight = FontWeight.Black); Text("Point of sale", fontSize = 11.sp, color = PosMuted) } },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { Text("KES", modifier = Modifier.padding(end = 16.dp), fontWeight = FontWeight.Bold, color = PosOrange) }
            )
        }
    ) { inset ->
        Column(
            Modifier.fillMaxSize().background(PosBg).padding(inset).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PosStat("Products", products.size.toString(), Modifier.weight(1f))
                PosStat("Sales recorded", sales.size.toString(), Modifier.weight(1f))
                PosStat("Today's sales", "KES " + sales.filter { System.currentTimeMillis() - it.at < 86400000 }.sumOf { it.total }.toString(), Modifier.weight(1f))
            }
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Products and services", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("Add your items, then tap Add to sale.", color = PosMuted, fontSize = 12.sp)
                        }
                        Button(onClick = { itemName = ""; itemPrice = ""; itemStock = "20"; showAdd = true }) { Text("＋ Add item") }
                    }
                    if (products.isEmpty()) {
                        Text("Your catalogue is empty. Add your first product or service to start taking sales.", color = PosMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                    }
                    products.forEach { product ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(product.name, fontWeight = FontWeight.SemiBold)
                                Text("KES ${product.price}  ·  Stock ${product.stock}", color = PosMuted, fontSize = 11.sp)
                            }
                            Button(
                                enabled = product.stock > 0,
                                onClick = {
                                    val inCart = cart.firstOrNull { it.productId == product.id }?.quantity ?: 0
                                    if (inCart >= product.stock) message = "No more stock available for ${product.name}."
                                    else {
                                        cart = if (inCart == 0) cart + PosCartLine(product.id, 1)
                                        else cart.map { if (it.productId == product.id) it.copy(quantity = it.quantity + 1) else it }
                                        message = "${product.name} added to sale."
                                    }
                                }
                            ) { Text("Add") }
                        }
                        HorizontalDivider(color = PosBg)
                    }
                }
            }

            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Current sale", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (cart.isEmpty()) Text("No items yet. Add a product above.", color = PosMuted, fontSize = 13.sp)
                    cart.forEach { line ->
                        val product = products.firstOrNull { it.id == line.productId } ?: return@forEach
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(product.name, fontWeight = FontWeight.SemiBold)
                                Text("KES ${product.price * line.quantity}", color = PosMuted, fontSize = 11.sp)
                            }
                            TextButton(onClick = {
                                cart = cart.mapNotNull { if (it.productId != line.productId) it else if (it.quantity <= 1) null else it.copy(quantity = it.quantity - 1) }
                            }) { Text("−") }
                            Text(line.quantity.toString(), fontWeight = FontWeight.Bold)
                            TextButton(onClick = {
                                if (line.quantity < product.stock) cart = cart.map { if (it.productId == line.productId) it.copy(quantity = it.quantity + 1) else it }
                                else message = "Only ${product.stock} in stock."
                            }) { Text("+") }
                        }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Total", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text("KES $total", fontWeight = FontWeight.Black, fontSize = 22.sp, color = PosOrange)
                    }
                    Text("Payment method", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Cash", "M-Pesa", "Card").forEach { method ->
                            FilterChip(selected = payment == method, onClick = { payment = method }, label = { Text(method) })
                        }
                    }
                    Button(
                        enabled = cart.isNotEmpty() && total > 0,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val summary = cart.mapNotNull { line ->
                                products.firstOrNull { it.id == line.productId }?.let { "${it.name} x${line.quantity}" }
                            }.joinToString(", ")
                            val sale = PosSale(UUID.randomUUID().toString(), System.currentTimeMillis(), total, payment, summary)
                            sales = listOf(sale) + sales
                            products = products.map { product ->
                                val sold = cart.firstOrNull { it.productId == product.id }?.quantity ?: 0
                                product.copy(stock = (product.stock - sold).coerceAtLeast(0))
                            }
                            saveSales(prefs, sales)
                            saveProducts(prefs, products)
                            cart = emptyList()
                            message = "Sale saved. Total KES ${sale.total} · ${sale.payment}."
                        }
                    ) { Text("Complete sale · KES $total") }
                }
            }

            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Recent sales", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (sales.isEmpty()) Text("Completed sales will appear here.", color = PosMuted, fontSize = 13.sp)
                    sales.take(10).forEach { sale ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("KES ${sale.total}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(sale.payment, color = PosOrange, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                            Text(sale.summary, color = PosMuted, fontSize = 11.sp)
                            Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(sale.at)), color = PosMuted, fontSize = 10.sp)
                        }
                        HorizontalDivider(color = PosBg)
                    }
                }
            }
            Text(message, color = PosMuted, fontSize = 11.sp)
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add product or service") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(itemName, { itemName = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(itemPrice, { itemPrice = it.filter(Char::isDigit) }, label = { Text("Price (KES)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    OutlinedTextField(itemStock, { itemStock = it.filter(Char::isDigit) }, label = { Text("Opening stock") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val price = itemPrice.toLongOrNull()
                    val stock = itemStock.toIntOrNull()
                    if (itemName.isBlank() || price == null || price <= 0 || stock == null || stock <= 0) {
                        message = "Enter an item name, a price above zero and opening stock."
                    } else {
                        products = products + PosProduct(UUID.randomUUID().toString(), itemName.trim(), price, stock)
                        saveProducts(prefs, products)
                        message = "${itemName.trim()} added to your catalogue."
                        showAdd = false
                    }
                }) { Text("Save item") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PosStat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(12.dp)) {
            Text(value, fontWeight = FontWeight.Black, fontSize = 18.sp, color = PosInk)
            Text(label, color = PosMuted, fontSize = 10.sp)
        }
    }
}
