import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val twigaProps = Properties().apply { val local = rootProject.file("twiga.properties"); if (local.exists()) local.inputStream().use { load(it) } }
fun setting(name: String, env: String): String = providers.gradleProperty(name).orNull ?: System.getenv(env) ?: twigaProps.getProperty(name) ?: ""
android {
    namespace = "co.ke.twiga.businessassistant"
    compileSdk = 35
    defaultConfig {
        applicationId = "co.ke.twiga.businessassistant"
        minSdk = 24
        targetSdk = 35
        // CI supplies monotonic codes so each published APK can be installed as an in-place update.
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("appVersionName").orNull ?: "1.0.0"
        buildConfigField("String", "SUPABASE_URL", "\"${setting("TWIGA_SUPABASE_URL","TWIGA_SUPABASE_URL").ifBlank { "https://ztyxxikkposfpxsutnfr.supabase.co" }}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${setting("TWIGA_SUPABASE_ANON_KEY","TWIGA_SUPABASE_ANON_KEY").ifBlank { "sb_publishable_Zex31DUxW6bX3LP6Wlos2Q_pMiZ_JeT" }}\"")
        buildConfigField("String", "VAPI_PUBLIC_KEY", "\"${setting("TWIGA_VAPI_PUBLIC_KEY","TWIGA_VAPI_PUBLIC_KEY").ifBlank { "2874d463-b31e-4d5a-84d7-e6cbe0a90b15" }}\"")
        buildConfigField("String", "VAPI_ASSISTANT_ID", "\"${setting("TWIGA_VAPI_ASSISTANT_ID","TWIGA_VAPI_ASSISTANT_ID").ifBlank { "ea3ae8f7-fb6f-430e-9c33-96d9a919ade3" }}\"")
        buildConfigField("String", "TWIGA_REPLY_API_URL", "\"${setting("TWIGA_REPLY_API_URL","TWIGA_REPLY_API_URL")}\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("ai.vapi.android:vapi:1.0.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
