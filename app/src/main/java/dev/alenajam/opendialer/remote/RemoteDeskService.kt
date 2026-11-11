package dev.alenajam.opendialer.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the paired handset SIM connected to the TWIGA workspace.
 * It syncs call state and executes owner-requested SIM dial/answer/end commands.
 * Cellular call audio is not captured or streamed to an AI provider.
 */
class RemoteDeskService : Service() {
    @Volatile private var running = false
    @Volatile private var fastUntil = 0L
    private var worker: Thread? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RemoteDesk.isPaired(this) || !RemoteDesk.remoteControl(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Never inherit a previous build's second-number bridge or silent auto-answer.
        RemoteDesk.prefs(this).edit()
            .putBoolean("auto_answer", false)
            .remove("ai_line")
            .remove("bridge_next_call")
            .apply()
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
        val manager = getSystemService(NotificationManager::class.java)
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("twiga_desk", "TWIGA remote desk", NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, "twiga_desk")
                .setContentTitle("TWIGA remote desk")
                .setContentText("Connected. Your dashboard can dial and answer calls.")
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("TWIGA remote desk")
                .setContentText("Connected. Your dashboard can dial and answer calls.")
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1101, notification)
        }
    }

    private fun loop() {
        while (running) {
            try {
                val token = RemoteDesk.token(this)
                if (token.isBlank() || !RemoteDesk.remoteControl(this)) {
                    stopSelf()
                    return
                }
                RemoteDesk.flush(this)
                val out = JSONObject(
                    RemoteDesk.rpc(
                        "device_sync",
                        JSONObject().put("p_token", token).put("p_state", CallTracker.state(this))
                    )
                )
                // Vapi/Retell are AI providers, not SIM dial targets. Keep the
                // provider setting for the dashboard but never call a second number.
                RemoteDesk.prefs(this).edit()
                    .putBoolean("ai", out.optBoolean("ai_enabled"))
                    .putBoolean("auto_answer", false)
                    .remove("ai_line")
                    .remove("bridge_next_call")
                    .apply()

                val commands = out.optJSONArray("commands") ?: JSONArray()
                for (i in 0 until commands.length()) {
                    val command = commands.getJSONObject(i)
                    fastUntil = System.currentTimeMillis() + 60000L
                    ui.post { run(token, command) }
                }
            } catch (_: Exception) {
                // Offline or server busy: retry on the next heartbeat.
            }
            val busy = CallTracker.state(this) != TelephonyManager.EXTRA_STATE_IDLE ||
                System.currentTimeMillis() < fastUntil
            try {
                Thread.sleep(if (busy) 3000L else 15000L)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun run(token: String, command: JSONObject) {
        val telecom = getSystemService(TelecomManager::class.java)
        var status = "done"
        var message = ""
        try {
            when (command.optString("action")) {
                "dial" -> {
                    val number = command.optString("number").trim()
                    if (number.isBlank()) throw IllegalArgumentException("No number")
                    RemoteDesk.prefs(this).edit()
                        .putString("dialing", number)
                        .putBoolean("auto_answer", false)
                        .remove("ai_line")
                        .remove("bridge_next_call")
                        .apply()
                    telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
                }
                "answer" -> telecom.acceptRingingCall()
                "decline", "hangup" -> if (!telecom.endCall()) {
                    throw IllegalStateException("No call to end")
                }
                else -> throw IllegalArgumentException("Unknown command")
            }
        } catch (_: SecurityException) {
            status = "failed"
            message = "Phone permission missing. Open TWIGA and check phone permissions."
        } catch (error: Exception) {
            status = "failed"
            message = error.message ?: "Failed"
        }
        val id = command.optString("id")
        Thread {
            runCatching {
                RemoteDesk.rpc(
                    "device_ack",
                    JSONObject().put("p_token", token).put("p_command_id", id)
                        .put("p_status", status).put("p_message", message)
                )
            }
        }.start()
    }
}
