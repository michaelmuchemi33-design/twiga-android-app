package dev.alenajam.opendialer.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Talks to the TWIGA dashboard backend. Only the public Supabase address and its
 * browser-safe publishable key live here. The phone proves who it is with a private
 * device token that is created when the owner pairs it with a 6-digit code.
 */
object RemoteDesk {
    private const val BASE = "https://ztyxxikkposfpxsutnfr.supabase.co"
    private const val KEY = "sb_publishable_Zex31DUxW6bX3LP6Wlos2Q_pMiZ_JeT"
    private val lock = Any()
    private val flushing = AtomicBoolean(false)

    fun prefs(c: Context) = c.getSharedPreferences("twiga_remote_desk", Context.MODE_PRIVATE)
    fun token(c: Context): String = prefs(c).getString("token", "") ?: ""
    fun business(c: Context): String = prefs(c).getString("business", "") ?: ""
    fun isPaired(c: Context): Boolean = token(c).isNotBlank()
    fun aiEnabled(c: Context): Boolean = prefs(c).getBoolean("ai", false)
    fun autoAnswerEnabled(c: Context): Boolean = prefs(c).getBoolean("auto_answer", false)
    fun setAutoAnswerEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("auto_answer", on).apply() }
    fun remoteControl(c: Context): Boolean = prefs(c).getBoolean("remote", true)
    fun setRemoteControl(c: Context, on: Boolean) { prefs(c).edit().putBoolean("remote", on).apply() }
    fun save(c: Context, token: String, business: String) {
        prefs(c).edit().putString("token", token).putString("business", business).apply()
    }
    fun unpair(c: Context) { prefs(c).edit().clear().apply() }

    /** Keeps call events on the phone until they reach the dashboard, so nothing is lost offline. */
    fun enqueue(c: Context, ev: JSONObject) {
        synchronized(lock) {
            val arr = JSONArray(prefs(c).getString("queue", "[]"))
            arr.put(ev)
            val keep = JSONArray()
            for (i in maxOf(0, arr.length() - 50) until arr.length()) keep.put(arr.get(i))
            prefs(c).edit().putString("queue", keep.toString()).apply()
        }
    }

    fun flush(c: Context) {
        val token = token(c)
        if (token.isBlank() || !flushing.compareAndSet(false, true)) return
        try {
            while (true) {
                val first = synchronized(lock) { JSONArray(prefs(c).getString("queue", "[]")).optJSONObject(0) } ?: break
                try {
                    rpc("device_event", JSONObject(first.toString()).put("p_token", token))
                } catch (e: Exception) {
                    break
                }
                synchronized(lock) {
                    val arr = JSONArray(prefs(c).getString("queue", "[]"))
                    arr.remove(0)
                    prefs(c).edit().putString("queue", arr.toString()).apply()
                }
            }
        } finally {
            flushing.set(false)
        }
    }

    /** Calls a database function and returns its JSON text. Throws IOException on any failure. */
    @Throws(IOException::class)
    fun rpc(name: String, body: JSONObject): String = rpcInternal(name, body, null)

    /** Calls an authenticated RPC using the current TWIGA WebView session. */
    @Throws(IOException::class)
    fun rpcAuthenticated(name: String, body: JSONObject, accessToken: String): String {
        require(accessToken.isNotBlank()) { "Sign in to TWIGA before connecting this phone." }
        return rpcInternal(name, body, accessToken)
    }

    private fun rpcInternal(name: String, body: JSONObject, accessToken: String?): String {
        val conn = URL("$BASE/rest/v1/rpc/$name").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.doOutput = true
            conn.setRequestProperty("apikey", KEY)
            if (!accessToken.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $accessToken")
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
