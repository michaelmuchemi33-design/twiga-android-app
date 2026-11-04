package dev.alenajam.opendialer.remote

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional "dial and answer from the dashboard" link. It checks in every 15 seconds while idle and
 * every 3 seconds during a call or just after a command. Call reporting itself does not need this
 * service (see CallStateReceiver). It does not record or capture call audio.
 */
class RemoteDeskService : Service() {
    @Volatile private var running = false
    @Volatile private var fastUntil = 0L
    private var worker: Thread? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RemoteDesk.isPaired(this) || !RemoteDesk.remoteControl(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            showNotice()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
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
        super.onDestroy()
    }

    private fun showNotice() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("twiga_desk", "TWIGA remote desk", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "twiga_desk")
            .setContentTitle("TWIGA remote desk")
            .setContentText("Connected. Your dashboard can dial and answer calls.")
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setOngoing(true)
            .build()
        startForeground(1101, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun loop() {
        while (running) {
            try {
                val token = RemoteDesk.token(this)
                if (token.isBlank() || !RemoteDesk.remoteControl(this)) { stopSelf(); return }
                RemoteDesk.flush(this)
                val out = JSONObject(RemoteDesk.rpc("device_sync", JSONObject().put("p_token", token).put("p_state", CallTracker.state(this))))
                RemoteDesk.prefs(this).edit().putBoolean("ai", out.optBoolean("ai_enabled")).apply()
                val cmds = out.optJSONArray("commands") ?: JSONArray()
                for (i in 0 until cmds.length()) {
                    val c = cmds.getJSONObject(i)
                    fastUntil = System.currentTimeMillis() + 60000L
                    ui.post { run(token, c) }
                }
            } catch (e: Exception) {
                // Offline or server busy: try again on the next round.
            }
            val busy = CallTracker.state(this) != TelephonyManager.EXTRA_STATE_IDLE || System.currentTimeMillis() < fastUntil
            try { Thread.sleep(if (busy) 3000L else 15000L) } catch (e: InterruptedException) { return }
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
                    RemoteDesk.prefs(this).edit().putString("dialing", n).apply()
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
