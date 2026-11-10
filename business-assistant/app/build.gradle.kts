plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
import java.util.Properties
val twigaProps = Properties().apply { val local = rootProject.file("twiga.properties"); if (local.exists()) local.inputStream().use { load(it) } }
fun setting(name: String, env: String): String = providers.gradleProperty(name).orNull ?: System.getenv(env) ?: twigaProps.getProperty(name) ?: ""
android {
    namespace = "co.ke.twiga.businessassistant"
    compileSdk = 35
    defaultConfig {
        applicationId = "co.ke.twiga.businessassistant"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "SUPABASE_URL", "\"${setting("TWIGA_SUPABASE_URL","TWIGA_SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${setting("TWIGA_SUPABASE_ANON_KEY","TWIGA_SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "VAPI_PUBLIC_KEY", "\"${setting("TWIGA_VAPI_PUBLIC_KEY","TWIGA_VAPI_PUBLIC_KEY")}\"")
        buildConfigField("String", "VAPI_ASSISTANT_ID", "\"${setting("TWIGA_VAPI_ASSISTANT_ID","TWIGA_VAPI_ASSISTANT_ID")}\"")
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
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20240303")
    implementation("ai.vapi.android:vapi:1.0.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
