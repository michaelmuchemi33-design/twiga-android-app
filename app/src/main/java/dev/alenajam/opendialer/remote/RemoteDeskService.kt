package dev.alenajam.opendialer.remote

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Keeps this phone connected to the TWIGA dashboard:
 *  - reports calls (ringing, active, ended, missed) as they happen on the SIM line
 *  - runs dial, answer, decline and hang-up commands sent from the dashboard
 *  - keeps the dashboard's AI switch in sync
 * It does not record or capture call audio.
 */
class RemoteDeskService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var registered = false
    private val ui = Handler(Looper.getMainLooper())
    private val events = ConcurrentLinkedQueue<JSONObject>()

    private var state = TelephonyManager.EXTRA_STATE_IDLE
    private var key = ""
    private var number = ""
    private var dir = "in"
    private var startMs = 0L
    private var connected = false
    private var dialing = ""

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            val st = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            onPhone(st, intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RemoteDesk.isPaired(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            showNotice()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!registered) {
            ContextCompat.registerReceiver(this, receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
        if (!running) {
            running = true
            worker = Thread { loop() }.also { it.isDaemon = true; it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        if (registered) { runCatching { unregisterReceiver(receiver) }; registered = false }
        super.onDestroy()
    }

    private fun showNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("twiga_desk", "TWIGA remote desk", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "twiga_desk")
            .setContentTitle("TWIGA remote desk")
            .setContentText("Connected. Your dashboard can dial and monitor calls.")
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setOngoing(true)
            .build()
        startForeground(1101, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun onPhone(st: String, num: String) {
        val now = System.currentTimeMillis()
        when (st) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (state != st) {
                    key = UUID.randomUUID().toString(); number = num; dir = "in"; startMs = now; connected = false
                    push("ringing", 0)
                } else if (num.isNotBlank() && num != number) {
                    number = num
                    push("ringing", 0)
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                    key = UUID.randomUUID().toString(); dir = "out"; number = dialing; dialing = ""
                }
                if (state != st) {
                    connected = true; startMs = now
                    push("active", 0)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (state != st && key.isNotEmpty()) {
                    val secs = if (connected) ((now - startMs) / 1000).toInt() else 0
                    push(if (!connected && dir == "in") "missed" else "ended", secs)
                    key = ""
                }
            }
        }
        state = st
    }

    private fun push(st: String, secs: Int) {
        events.add(JSONObject().put("p_key", key).put("p_direction", dir).put("p_number", number).put("p_state", st).put("p_duration", secs))
    }

    private fun loop() {
        while (running) {
            try {
                val token = RemoteDesk.token(this)
                if (token.isBlank()) { stopSelf(); return }
                while (true) {
                    val e = events.peek() ?: break
                    RemoteDesk.rpc("device_event", e.put("p_token", token))
                    events.poll()
                }
                val out = JSONObject(RemoteDesk.rpc("device_sync", JSONObject().put("p_token", token).put("p_state", state)))
                RemoteDesk.prefs(this).edit().putBoolean("ai", out.optBoolean("ai_enabled")).apply()
                val cmds = out.optJSONArray("commands") ?: JSONArray()
                for (i in 0 until cmds.length()) {
                    val c = cmds.getJSONObject(i)
                    ui.post { run(token, c) }
                }
            } catch (e: Exception) {
                // Offline or server busy: try again on the next round.
            }
            try { Thread.sleep(2500) } catch (e: InterruptedException) { return }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun run(token: String, c: JSONObject) {
        val tm = getSystemService(TelecomManager::class.java)
        var status = "done"
        var msg = ""
        try {
            when (c.optString("action")) {
                "dial" -> {
                    val n = c.optString("number").trim()
                    if (n.isBlank()) throw IllegalArgumentException("No number")
                    dialing = n
                    tm.placeCall(Uri.fromParts("tel", n, null), Bundle())
                }
                "answer" -> tm.acceptRingingCall()
                "decline", "hangup" -> if (!tm.endCall()) throw IllegalStateException("No call to end")
                else -> throw IllegalArgumentException("Unknown command")
            }
        } catch (e: SecurityException) {
            status = "failed"; msg = "Phone permission missing. Open the app and tap Check phone permissions."
        } catch (e: Exception) {
            status = "failed"; msg = e.message ?: "Failed"
        }
        val id = c.optString("id")
        Thread {
            runCatching {
                RemoteDesk.rpc("device_ack", JSONObject().put("p_token", token).put("p_command_id", id).put("p_status", status).put("p_message", msg))
            }
        }.start()
    }
}
