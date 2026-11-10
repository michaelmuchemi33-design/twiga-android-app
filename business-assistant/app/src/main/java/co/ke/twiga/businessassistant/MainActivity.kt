package co.ke.twiga.businessassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.vapi.android.Vapi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val Orange = Color(0xFFF47721)
private val Ink = Color(0xFF171717)
private val Paper = Color(0xFFF7F7F5)
private val Muted = Color(0xFF77736E)
private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
private val jsonType = "application/json".toMediaType()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("twiga_business", MODE_PRIVATE)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Orange, onPrimary = Ink, background = Paper, surface = Color.White)) {
                BusinessApp(
                    p.getString("url", BuildConfig.SUPABASE_URL).orEmpty(),
                    p.getString("key", BuildConfig.SUPABASE_ANON_KEY).orEmpty(),
                    p.getString("vapi", BuildConfig.VAPI_PUBLIC_KEY).orEmpty(),
                    p.getString("assistant", BuildConfig.VAPI_ASSISTANT_ID).orEmpty(),
                    p.getString("replyApi", BuildConfig.TWIGA_REPLY_API_URL).orEmpty()
                ) { u,k,v,a,reply -> p.edit().putString("url",u).putString("key",k).putString("vapi",v).putString("assistant",a).putString("replyApi",reply).apply() }
            }
        }
    }
}

data class TwigaSession(val token:String, val userId:String, val email:String)
data class TwigaRecord(val id:String, val name:String, val detail:String, val status:String)
data class TwigaBusinessProfile(
    val businessSummary:String = "", val whatTheySell:String = "", val whatTheyDo:String = "",
    val productsAndServices:String = "", val targetCustomers:String = "", val uniqueSellingPoints:String = "",
    val serviceArea:String = "", val openingHours:String = "", val deliveryAndPayment:String = "",
    val returnsAndRefunds:String = "", val frequentlyAskedQuestions:String = "",
    val brandVoice:String = "warm, helpful and professional", val replyLanguage:String = "match_customer",
    val assistantInstructions:String = ""
) {
    fun toJson(businessId:String,userId:String) = JSONObject()
        .put("business_id",businessId).put("updated_by",userId)
        .put("business_summary",businessSummary).put("what_they_sell",whatTheySell)
        .put("what_they_do",whatTheyDo).put("products_and_services",productsAndServices)
        .put("target_customers",targetCustomers).put("unique_selling_points",uniqueSellingPoints)
        .put("service_area",serviceArea).put("opening_hours",openingHours)
        .put("delivery_and_payment",deliveryAndPayment).put("returns_and_refunds",returnsAndRefunds)
        .put("frequently_asked_questions",frequentlyAskedQuestions).put("brand_voice",brandVoice)
        .put("reply_language",replyLanguage).put("assistant_instructions",assistantInstructions)
    companion object {
        fun fromJson(j:JSONObject?) = TwigaBusinessProfile(
            j?.optString("business_summary","") ?: "", j?.optString("what_they_sell","") ?: "",
            j?.optString("what_they_do","") ?: "", j?.optString("products_and_services","") ?: "",
            j?.optString("target_customers","") ?: "", j?.optString("unique_selling_points","") ?: "",
            j?.optString("service_area","") ?: "", j?.optString("opening_hours","") ?: "",
            j?.optString("delivery_and_payment","") ?: "", j?.optString("returns_and_refunds","") ?: "",
            j?.optString("frequently_asked_questions","") ?: "",
            j?.optString("brand_voice","warm, helpful and professional") ?: "warm, helpful and professional",
            j?.optString("reply_language","match_customer") ?: "match_customer",
            j?.optString("assistant_instructions","") ?: ""
        )
    }
}
data class TwigaProduct(
    val id:String, val name:String, val sku:String, val category:String, val description:String,
    val price:Double, val currency:String, val unit:String, val stock:Double, val imageKey:String,
    val imageUrl:String
)
class TwigaApi(private val url:String, private val key:String) {
    private suspend fun call(path:String, token:String, method:String="GET", body:JSONObject?=null):String = withContext(Dispatchers.IO) {
        val b=Request.Builder().url(url.trimEnd('/')+path).header("apikey",key).header("Authorization","Bearer $token").header("Content-Type","application/json").header("Prefer","return=representation")
        when(method) { "POST" -> b.post((body ?: JSONObject()).toString().toRequestBody(jsonType)); "PATCH" -> b.patch((body ?: JSONObject()).toString().toRequestBody(jsonType)); else -> b.get() }
        http.newCall(b.build()).execute().use { r ->
            val raw=r.body?.string().orEmpty()
            if(!r.isSuccessful) throw IllegalStateException("Server ${r.code}: ${raw.take(180)}")
            raw
        }
    }
    suspend fun login(email:String,password:String):TwigaSession = withContext(Dispatchers.IO) {
        val req=Request.Builder().url(url.trimEnd('/')+"/auth/v1/token?grant_type=password").header("apikey",key).post(JSONObject().put("email",email).put("password",password).toString().toRequestBody(jsonType)).build()
        http.newCall(req).execute().use { r ->
            val raw=r.body?.string().orEmpty()
            if(!r.isSuccessful) throw IllegalStateException(JSONObject(raw).optString("msg","Sign-in failed (${r.code})"))
            val j=JSONObject(raw); val user=j.getJSONObject("user")
            TwigaSession(j.getString("access_token"),user.getString("id"),user.optString("email",email))
        }
    }
    suspend fun businesses(s:TwigaSession):List<TwigaRecord> {
        val raw=call("/rest/v1/business_members?select=business_id,businesses(id,name)&user_id=eq.${s.userId}",s.token)
        val a=JSONArray(raw)
        return (0 until a.length()).mapNotNull { i -> a.getJSONObject(i).optJSONObject("businesses")?.let { TwigaRecord(it.optString("id"),it.optString("name","Business"),"","") } }
    }
    suspend fun list(table:String,s:TwigaSession,businessId:String):List<TwigaRecord> {
        val a=JSONArray(call("/rest/v1/$table?select=*&business_id=eq.$businessId&order=created_at.desc&limit=50",s.token))
        return (0 until a.length()).map { i ->
            val j=a.getJSONObject(i)
            TwigaRecord(j.optString("id"),j.optString("name",j.optString("title",j.optString("customer_name","Record"))),j.optString("phone",j.optString("description",j.optString("notes",j.optString("total_amount","")))),j.optString("status",""))
        }
    }
    suspend fun add(table:String,s:TwigaSession,businessId:String,data:JSONObject) {
        data.put("business_id",businessId).put("created_by",s.userId)
        call("/rest/v1/$table",s.token,"POST",data)
    }
    suspend fun loadProfile(s:TwigaSession,businessId:String):TwigaBusinessProfile {
        val rows=JSONArray(call("/rest/v1/twiga_business_profiles?select=*&business_id=eq.$businessId&limit=1",s.token))
        return TwigaBusinessProfile.fromJson(if(rows.length()>0) rows.getJSONObject(0) else null)
    }
    suspend fun saveProfile(s:TwigaSession,businessId:String,profile:TwigaBusinessProfile) = withContext(Dispatchers.IO) {
        val existing=JSONArray(call("/rest/v1/twiga_business_profiles?select=business_id&business_id=eq.$businessId&limit=1",s.token)).length()>0
        val method=if(existing) "PATCH" else "POST"
        val path=if(existing) "/rest/v1/twiga_business_profiles?business_id=eq.$businessId" else "/rest/v1/twiga_business_profiles"
        val request=Request.Builder().url(url.trimEnd('/')+path).header("apikey",key)
            .header("Authorization","Bearer ${s.token}").header("Content-Type","application/json")
            .header("Prefer","return=representation").method(method,profile.toJson(businessId,s.userId).toString().toRequestBody(jsonType)).build()
        http.newCall(request).execute().use { r ->
            val raw=r.body?.string().orEmpty()
            if(!r.isSuccessful) throw IllegalStateException("Profile save failed (${r.code}): ${raw.take(180)}")
        }
    }
    suspend fun listProducts(s:TwigaSession,businessId:String,replyApiUrl:String):List<TwigaProduct> {
        val raw=call("/rest/v1/twiga_products?select=id,name,sku,category,description,price,currency,unit,stock_quantity,image_key&business_id=eq.$businessId&order=name.asc&limit=200",s.token)
        val rows=JSONArray(raw)
        return (0 until rows.length()).map { i ->
            val j=rows.getJSONObject(i)
            val imageKey=j.optString("image_key","")
            val imageUrl=if(imageKey.isNotBlank()&&replyApiUrl.isNotBlank()) signedProductImage(replyApiUrl,s,businessId,imageKey) else ""
            TwigaProduct(j.optString("id"),j.optString("name"),j.optString("sku"),j.optString("category"),
                j.optString("description"),j.optDouble("price"),j.optString("currency","KES"),j.optString("unit","piece"),
                j.optDouble("stock_quantity"),imageKey,imageUrl)
        }
    }
    suspend fun addProduct(s:TwigaSession,businessId:String,name:String,sku:String,category:String,description:String,price:Double,stock:Double,imageKey:String) {
        val data=JSONObject().put("business_id",businessId).put("created_by",s.userId).put("name",name)
            .put("sku",sku.ifBlank { JSONObject.NULL }).put("category",category).put("description",description)
            .put("price",price).put("currency","KES").put("unit","piece").put("stock_quantity",stock)
            .put("image_key",imageKey.ifBlank { JSONObject.NULL }).put("is_active",true)
        call("/rest/v1/twiga_products",s.token,"POST",data)
    }
    suspend fun uploadProductImage(replyApiUrl:String,s:TwigaSession,businessId:String,contentType:String,bytes:ByteArray):String = withContext(Dispatchers.IO) {
        if(replyApiUrl.isBlank()) throw IllegalStateException("Set the TWIGA Reply service URL first. Image storage is not configured yet.")
        val ticketBody=JSONObject().put("businessId",businessId).put("contentType",contentType).put("sizeBytes",bytes.size)
        val ticketReq=Request.Builder().url(replyApiUrl.trimEnd('/')+"/api/catalog/upload-url").header("Authorization","Bearer ${s.token}")
            .post(ticketBody.toString().toRequestBody(jsonType)).build()
        val ticketRaw=http.newCall(ticketReq).execute().use { r ->
            val raw=r.body?.string().orEmpty()
            if(!r.isSuccessful) throw IllegalStateException("Image upload preparation failed (${r.code}): ${raw.take(180)}")
            raw
        }
        val ticket=JSONObject(ticketRaw)
        val put=Request.Builder().url(ticket.getString("uploadUrl")).put(bytes.toRequestBody(contentType.toMediaType())).build()
        http.newCall(put).execute().use { r -> if(!r.isSuccessful) throw IllegalStateException("Cloudflare image upload failed (${r.code})") }
        ticket.getString("imageKey")
    }
    private suspend fun signedProductImage(replyApiUrl:String,s:TwigaSession,businessId:String,key:String):String = withContext(Dispatchers.IO) {
        val req=Request.Builder().url(replyApiUrl.trimEnd('/')+"/api/catalog/image-url?businessId="+Uri.encode(businessId)+"&key="+Uri.encode(key))
            .header("Authorization","Bearer ${s.token}").get().build()
        http.newCall(req).execute().use { r ->
            val raw=r.body?.string().orEmpty()
            if(!r.isSuccessful) throw IllegalStateException("Could not load product photo (${r.code})")
            JSONObject(raw).optString("imageUrl","")
        }
    }
    suspend fun voiceTicket(s:TwigaSession,businessId:String):String {
        val raw=call("/functions/v1/twiga-vapi-session",s.token,"POST",JSONObject().put("businessId",businessId))
        return JSONObject(raw).getString("voiceSessionToken")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BusinessApp(initialUrl:String,initialKey:String,initialVapi:String,initialAssistant:String,initialReplyApiUrl:String,save:(String,String,String,String,String)->Unit) {
    var url by remember { mutableStateOf(initialUrl) }; var key by remember { mutableStateOf(initialKey) }
    var vapiKey by remember { mutableStateOf(initialVapi) }; var assistantId by remember { mutableStateOf(initialAssistant) }
    var replyApiUrl by remember { mutableStateOf(initialReplyApiUrl) }
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<TwigaSession?>(null) }; var business by remember { mutableStateOf<TwigaRecord?>(null) }
    var businessList by remember { mutableStateOf(emptyList<TwigaRecord>()) }; var page by remember { mutableStateOf("Overview") }
    var records by remember { mutableStateOf(emptyList<TwigaRecord>()) }; var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }; var dialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }; var phone by remember { mutableStateOf("") }; var notes by remember { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf("Ready to connect") }; var voiceLines by remember { mutableStateOf(emptyList<String>()) }
    var profile by remember { mutableStateOf(TwigaBusinessProfile()) }; var profileLoading by remember { mutableStateOf(false) }
    var products by remember { mutableStateOf(emptyList<TwigaProduct>()) }; var productLoading by remember { mutableStateOf(false) }
    var productDialog by remember { mutableStateOf(false) }; var productPhotoKey by remember { mutableStateOf("") }
    var productPhotoLabel by remember { mutableStateOf("") }
    var productName by remember { mutableStateOf("") }; var productSku by remember { mutableStateOf("") }
    var productCategory by remember { mutableStateOf("") }; var productDescription by remember { mutableStateOf("") }
    var productPrice by remember { mutableStateOf("") }; var productStock by remember { mutableStateOf("0") }
    val scope= rememberCoroutineScope(); val api=remember(url,key){TwigaApi(url,key)}
    val context=androidx.compose.ui.platform.LocalContext.current
    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri:Uri? ->
        if(uri!=null) {
            val activeSession=session; val activeBusiness=business
            if(activeSession==null||activeBusiness==null) error="Sign in and select a business before adding product photos."
            else scope.launch {
                try {
                    val mime=context.contentResolver.getType(uri) ?: "image/jpeg"
                    val bytes=context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Could not read selected image")
                    if(bytes.size>5*1024*1024) throw IllegalStateException("Choose an image smaller than 5 MB")
                    if(mime !in listOf("image/jpeg","image/png","image/webp")) throw IllegalStateException("Choose a JPG, PNG or WebP image")
                    productPhotoKey=api.uploadProductImage(replyApiUrl,activeSession,activeBusiness.id,mime,bytes)
                    productPhotoLabel="Photo uploaded to this business's private storage"
                    error=""
                } catch(e:Exception) { error=e.message ?: "Photo upload failed" }
            }
        }
    }
    fun loadProfile() {
        val activeSession=session ?: return; val activeBusiness=business ?: return
        profileLoading=true
        scope.launch { try { profile=api.loadProfile(activeSession,activeBusiness.id) } catch(e:Exception) { error=e.message ?: "Could not load business profile" }; profileLoading=false }
    }
    fun saveProfile() {
        val activeSession=session ?: return; val activeBusiness=business ?: return
        profileLoading=true
        scope.launch { try { api.saveProfile(activeSession,activeBusiness.id,profile); error="" } catch(e:Exception) { error=e.message ?: "Could not save business profile" }; profileLoading=false }
    }
    fun reloadProducts() {
        val activeSession=session ?: return; val activeBusiness=business ?: return
        productLoading=true
        scope.launch { try { products=api.listProducts(activeSession,activeBusiness.id,replyApiUrl); error="" } catch(e:Exception) { error=e.message ?: "Could not load products" }; productLoading=false }
    }
    fun saveProduct() {
        val activeSession=session ?: return; val activeBusiness=business ?: return
        val price=productPrice.toDoubleOrNull(); val stock=productStock.toDoubleOrNull()
        if(productName.isBlank()||price==null||price<0||stock==null||stock<0) { error="Enter a product name and valid price and stock."; return }
        productLoading=true
        scope.launch { try {
            api.addProduct(activeSession,activeBusiness.id,productName.trim(),productSku.trim(),productCategory.trim(),productDescription.trim(),price,stock,productPhotoKey)
            productDialog=false; productName="";productSku="";productCategory="";productDescription="";productPrice="";productStock="0";productPhotoKey="";productPhotoLabel=""
            reloadProducts()
        } catch(e:Exception) { error=e.message ?: "Could not save product"; productLoading=false } }
    }
    val table=when(page) { "Customers"->"twiga_customers"; "Orders"->"twiga_orders"; "Appointments"->"twiga_appointments"; else->"twiga_tasks" }
    fun reload(forPage:String=page) {
        val s=session ?: return; val b=business ?: return
        val selectedTable=when(forPage) { "Customers"->"twiga_customers"; "Orders"->"twiga_orders"; "Appointments"->"twiga_appointments"; else->"twiga_tasks" }
        loading=true; error=""
        scope.launch { try { records=api.list(selectedTable,s,b.id) } catch(e:Exception) { error=e.message ?: "Could not load records" }; loading=false }
    }
    fun login() {
        loading=true; error=""
        scope.launch { try { val s=api.login(email.trim(),password); session=s; businessList=api.businesses(s); business=businessList.firstOrNull(); if(business==null) error="No business is linked to this login. Ask the owner to add you in TWIGA."; }
            catch(e:Exception) { error=e.message ?: "Sign-in failed" }; loading=false }
    }
    fun addRecord() {
        val s=session ?: return; val b=business ?: return
        val data=JSONObject()
        when(page) {
            "Customers" -> data.put("name",name).put("phone",phone).put("notes",notes)
            "Orders" -> data.put("customer_name",name).put("total_amount",phone.toDoubleOrNull() ?: 0.0).put("description",notes).put("status","pending")
            "Appointments" -> data.put("customer_name",name).put("phone",phone).put("notes",notes).put("status","scheduled")
            else -> data.put("title",name).put("description",notes).put("status","open")
        }
        loading=true; scope.launch { try { api.add(table,s,b.id,data); dialog=false; name="";phone="";notes="";reload() } catch(e:Exception) { error=e.message ?: "Save failed" }; loading=false }
    }
    if(session==null) {
        Column(Modifier.fillMaxSize().background(Paper).padding(24.dp),verticalArrangement=Arrangement.Center) {
            Brand()
            Spacer(Modifier.height(28.dp)); Text("Your business, in hand.",fontSize=27.sp,fontWeight=FontWeight.Bold,color=Ink)
            Text("Customers, orders, appointments and your AI assistant in one place.",color=Muted)
            Spacer(Modifier.height(22.dp))
            OutlinedTextField(url,{url=it},label={Text("Supabase project URL")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(key,{key=it},label={Text("Supabase publishable / anon key")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(replyApiUrl,{replyApiUrl=it},label={Text("TWIGA Reply service URL (optional until deployed)")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(email,{email=it},label={Text("Email")},modifier=Modifier.fillMaxWidth(),singleLine=true)
            OutlinedTextField(password,{password=it},label={Text("Password")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true)
            Spacer(Modifier.height(14.dp)); Button(onClick={save(url,key,vapiKey,assistantId,replyApiUrl);login()},enabled=!loading&&url.isNotBlank()&&key.isNotBlank()&&email.isNotBlank()&&password.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text(if(loading)"Signing in…" else "Sign in") }
            if(error.isNotBlank()) Text(error,color=Color(0xFFB3261E),modifier=Modifier.padding(top=12.dp))
            Text("Sign in with the same account used on app.twiga.co.ke.",fontSize=12.sp,color=Muted,modifier=Modifier.padding(top=16.dp))
        }
    } else {
        Scaffold(topBar={TopAppBar(title={Column { Text("TWIGA",fontWeight=FontWeight.Black); Text(business?.name ?: "Business Assistant",fontSize=12.sp,color=Muted) }},actions={if(page!="Overview") TextButton(onClick={page="Overview"}){Text("Home")}
                TextButton(onClick={session=null;business=null}){Text("Sign out")}})},
            bottomBar={NavigationBar { listOf("Overview","Customers","Orders","Appointments","Tasks","Voice").forEach { item ->
                NavigationBarItem(selected=page==item,onClick={page=item;if(item!="Overview"&&item!="Voice")reload(item)},
                    icon={Icon(when(item){"Overview"->Icons.Default.Home;"Customers"->Icons.Default.People;"Orders"->Icons.Default.ReceiptLong;"Appointments"->Icons.Default.CalendarMonth;"Tasks"->Icons.Default.CheckCircle;else->Icons.Default.Mic},null)},
                    label={Text(item,fontSize=9.sp)})
            } }}) { padding ->
            Column(Modifier.fillMaxSize().background(Paper).padding(padding).padding(16.dp)) {
                if(error.isNotBlank()) Text(error,color=Color(0xFFB3261E),modifier=Modifier.padding(8.dp))
                when(page) {
                    "Overview" -> {
                        Text("Good business starts here.",fontSize=24.sp,fontWeight=FontWeight.Bold,color=Ink); Text("Your daily workspace",color=Muted)
                        Spacer(Modifier.height(16.dp))
                        listOf("Business Profile","POS Products","Customers","Orders","Appointments","Tasks","Voice").forEach { item ->
                            Card(Modifier.fillMaxWidth().padding(bottom=10.dp).clickable{
                                page=if(item=="POS Products") "Products" else item
                                when(item) { "Business Profile"->loadProfile(); "POS Products"->reloadProducts(); else->if(item!="Voice")reload(item) }
                            },colors=CardDefaults.cardColors(containerColor=Color.White)) {
                                Row(Modifier.padding(18.dp).fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                    Icon(when(item){"Customers"->Icons.Default.People;"Orders"->Icons.Default.ReceiptLong;"Appointments"->Icons.Default.CalendarMonth;"Tasks"->Icons.Default.CheckCircle;else->Icons.Default.Mic},null,tint=Orange,modifier=Modifier.size(28.dp))
                                    Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)){Text(item,fontWeight=FontWeight.SemiBold);Text("Open $item workspace",fontSize=12.sp,color=Muted)}; Icon(Icons.Default.ChevronRight,null)
                                }
                            }
                        }
                        if(businessList.size>1) { Text("Switch business",fontWeight=FontWeight.Bold); businessList.forEach { TextButton(onClick={business=it}){Text(it.name)} } }
                    }
                    "Business Profile" -> BusinessProfileWorkspace(profile,profileLoading,{profile=it},{saveProfile()})
                    "Products" -> ProductsWorkspace(products,productLoading,{productDialog=true;productPhotoKey="";productPhotoLabel=""},{reloadProducts()})
                    "Voice" -> VoiceWorkspace(vapiKey,assistantId,business?.id.orEmpty(),session,api,{voiceStatus=it},{voiceLines=voiceLines+it},voiceStatus,voiceLines,{vapiKey=it;save(url,key,it,assistantId,replyApiUrl)},{assistantId=it;save(url,key,vapiKey,it,replyApiUrl)})
                    else -> {
                        Row(verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)){Text(page,fontSize=24.sp,fontWeight=FontWeight.Bold);Text("Business records",color=Muted,fontSize=12.sp)}; Button(onClick={dialog=true}){Icon(Icons.Default.Add,null);Text("Add")} }
                        if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if(records.isEmpty()&&!loading) Text("Nothing here yet. Tap Add to create the first record.",color=Muted,modifier=Modifier.padding(18.dp))
                        LazyColumn { items(records) { r -> Card(Modifier.fillMaxWidth().padding(bottom=8.dp),colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(15.dp)){Text(r.name,fontWeight=FontWeight.SemiBold);if(r.detail.isNotBlank())Text(r.detail,color=Muted,fontSize=13.sp);if(r.status.isNotBlank())Text(r.status.uppercase(),color=Orange,fontSize=11.sp)}} } }
                    }
                }
            }
        }
    }
    if(productDialog) ProductDialog(
        name=productName,sku=productSku,category=productCategory,description=productDescription,price=productPrice,stock=productStock,
        photoLabel=productPhotoLabel,loading=productLoading,
        onName={productName=it},onSku={productSku=it},onCategory={productCategory=it},onDescription={productDescription=it},
        onPrice={productPrice=it},onStock={productStock=it},onPickPhoto={imagePicker.launch("image/*")},
        onSave={saveProduct()},onDismiss={productDialog=false}
    )
    if(dialog) AlertDialog(onDismissRequest={dialog=false},title={Text("Add $page")},text={Column {
        OutlinedTextField(name,{name=it},label={Text(if(page=="Tasks")"Task title" else "Name / customer")},modifier=Modifier.fillMaxWidth())
        if(page!="Tasks") OutlinedTextField(phone,{phone=it},label={Text(if(page=="Orders")"Amount (KSh)" else "Phone number")},modifier=Modifier.fillMaxWidth())
        OutlinedTextField(notes,{notes=it},label={Text("Notes / details")},modifier=Modifier.fillMaxWidth())
    }},confirmButton={TextButton(onClick={ { addRecord() } },enabled=name.isNotBlank()){Text("Save")}},dismissButton={TextButton(onClick={dialog=false}){Text("Cancel")}})
}

@Composable
private fun Brand() {
    Row(verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(Orange,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){Text("T",fontSize=26.sp,fontWeight=FontWeight.Black,color=Ink)}
        Spacer(Modifier.width(12.dp)); Column { Text("TWIGA",fontSize=22.sp,fontWeight=FontWeight.Black,color=Ink); Text("BUSINESS ASSISTANT",fontSize=10.sp,letterSpacing=2.sp,color=Muted) }
    }
}

@Composable
private fun VoiceWorkspace(key:String,assistant:String,businessId:String,session:TwigaSession?,api:TwigaApi,setStatus:(String)->Unit,addLine:(String)->Unit,status:String,lines:List<String>,setKey:(String)->Unit,setAssistant:(String)->Unit) {
    var publicKey by remember(key){mutableStateOf(key)}
    var assistantId by remember(assistant){mutableStateOf(assistant)}
    var sdk by remember { mutableStateOf<Vapi?>(null) }
    var connecting by remember { mutableStateOf(false) }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val context=androidx.compose.ui.platform.LocalContext.current
    val scope= rememberCoroutineScope()
    LaunchedEffect(sdk) {
        val active=sdk ?: return@LaunchedEffect
        active.eventFlow.collect { event ->
            when(event) {
                is Vapi.Event.CallDidStart -> setStatus("Connected")
                is Vapi.Event.CallDidEnd -> { setStatus("Call ended"); sdk=null }
                is Vapi.Event.Transcript -> addLine(event.text)
                is Vapi.Event.SpeechUpdate -> setStatus("TWIGA is speaking")
                is Vapi.Event.Error -> setStatus("Voice error: ${event.error}")
                else -> Unit
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text("Talk to TWIGA",fontSize=25.sp,fontWeight=FontWeight.Bold)
        Text("In-app voice. No phone number required.",color=Muted)
        OutlinedTextField(publicKey,{publicKey=it;setKey(it)},label={Text("Vapi public key")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(assistantId,{assistantId=it;setAssistant(it)},label={Text("Vapi assistant ID")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth().weight(1f),colors=CardDefaults.cardColors(containerColor=Color.White)) {
            Column(Modifier.padding(14.dp)) {
                Text(status,fontWeight=FontWeight.Bold,color=Orange)
                Spacer(Modifier.height(8.dp))
                if(lines.isEmpty()) Text("Your conversation transcript will appear here.",color=Muted)
                LazyColumn { items(lines){Text(it,modifier=Modifier.padding(vertical=4.dp))} }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick={
            if(sdk!=null){sdk?.stop();sdk=null;setStatus("Call ended")}
            else if(publicKey.isBlank()||assistantId.isBlank())setStatus("Enter your Vapi public key and assistant ID.")
            else if(session==null||businessId.isBlank())setStatus("Sign in and select a business first.")
            else {
                connecting=true;setStatus("Securing voice session…")
                scope.launch {
                    try {
                        val ticket=api.voiceTicket(session,businessId)
                        val v=Vapi(context,lifecycle,Vapi.Configuration(publicKey=publicKey))
                        sdk=v
                        setStatus("Connecting to TWIGA…")
                        v.start(assistantId=assistantId,metadata=mapOf("businessId" to businessId,"userId" to session.userId,"twigaVoiceSession" to ticket))
                            .onSuccess{setStatus("Connected");addLine("Secure TWIGA voice session connected.")}
                            .onFailure{setStatus("Connection failed: ${it.message}");sdk=null}
                    } catch(e:Exception) { setStatus("Could not start voice: ${e.message}") }
                    connecting=false
                }
            }
        },enabled=!connecting,modifier=Modifier.fillMaxWidth()) {
            Icon(if(sdk==null)Icons.Default.Mic else Icons.Default.CallEnd,null)
            Spacer(Modifier.width(8.dp))
            Text(if(connecting)"Connecting…" else if(sdk==null)"Start voice assistant" else "End conversation")
        }
    }
}
