package dev.alenajam.opendialer.remote

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the TWIGA dashboard backend. Only the public Supabase address and its
 * browser-safe publishable key live here. The phone proves who it is with a private
 * device token that is created when the owner pairs it with a 6-digit code.
 */
object RemoteDesk {
    private const val BASE = "https://ztyxxikkposfpxsutnfr.supabase.co"
    private const val KEY = "sb_publishable_Zex31DUxW6bX3LP6Wlos2Q_pMiZ_JeT"

    fun prefs(c: Context) = c.getSharedPreferences("twiga_remote_desk", Context.MODE_PRIVATE)
    fun token(c: Context): String = prefs(c).getString("token", "") ?: ""
    fun business(c: Context): String = prefs(c).getString("business", "") ?: ""
    fun isPaired(c: Context): Boolean = token(c).isNotBlank()
    fun aiEnabled(c: Context): Boolean = prefs(c).getBoolean("ai", false)
    fun save(c: Context, token: String, business: String) {
        prefs(c).edit().putString("token", token).putString("business", business).apply()
    }
    fun unpair(c: Context) { prefs(c).edit().clear().apply() }

    /** Calls a database function and returns its JSON text. Throws IOException on any failure. */
    @Throws(IOException::class)
    fun rpc(name: String, body: JSONObject): String {
        val conn = URL("$BASE/rest/v1/rpc/$name").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.setRequestProperty("apikey", KEY)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                throw IOException(if (msg.isNullOrBlank()) "HTTP $code" else msg)
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
