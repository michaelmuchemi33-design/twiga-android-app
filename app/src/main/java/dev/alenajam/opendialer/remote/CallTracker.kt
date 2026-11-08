package dev.alenajam.opendialer.remote

import android.content.Context
import android.telephony.TelephonyManager
import org.json.JSONObject
import java.util.UUID

/** Turns phone-state changes into call events. State lives in preferences so it survives process death. */
object CallTracker {
    private fun sp(c: Context) = c.getSharedPreferences("twiga_call_state", Context.MODE_PRIVATE)

    fun state(c: Context): String =
        sp(c).getString("state", TelephonyManager.EXTRA_STATE_IDLE) ?: TelephonyManager.EXTRA_STATE_IDLE

    /** Telecom provides caller ID even when Android redacts it from PHONE_STATE broadcasts. */
    @Synchronized
    fun enrichNumber(c: Context, rawNumber: String) {
        val number = rawNumber.trim().take(30)
        if (number.isBlank()) return
        val p = sp(c)
        val key = p.getString("key", "") ?: ""
        if (key.isBlank()) {
            p.edit().putString("pending_number", number).apply()
            return
        }
        if ((p.getString("number", "") ?: "") == number) return
        p.edit().putString("number", number).apply()
        val state = p.getString("state", TelephonyManager.EXTRA_STATE_IDLE) ?: TelephonyManager.EXTRA_STATE_IDLE
        val dir = p.getString("dir", "in") ?: "in"
        if (state == TelephonyManager.EXTRA_STATE_RINGING || state == TelephonyManager.EXTRA_STATE_OFFHOOK) {
            RemoteDesk.enqueue(c, JSONObject().put("p_key", key).put("p_direction", dir)
                .put("p_number", number).put("p_state", if (state == TelephonyManager.EXTRA_STATE_RINGING) "ringing" else "active")
                .put("p_duration", 0))
        }
    }

    @Synchronized
    fun onPhone(c: Context, st: String, num: String) {
        val p = sp(c)
        val prev = state(c)
        var key = p.getString("key", "") ?: ""
        var number = p.getString("number", "") ?: ""
        var dir = p.getString("dir", "in") ?: "in"
        var startMs = p.getLong("start", 0L)
        var connected = p.getBoolean("connected", false)
        val now = System.currentTimeMillis()

        fun push(s: String, secs: Int) {
            RemoteDesk.enqueue(
                c,
                JSONObject().put("p_key", key).put("p_direction", dir).put("p_number", number)
                    .put("p_state", s).put("p_duration", secs)
            )
        }

        when (st) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (prev != st) {
                    key = UUID.randomUUID().toString(); number = num.ifBlank { p.getString("pending_number", "") ?: "" }; dir = "in"; startMs = now; connected = false
                    p.edit().remove("pending_number").apply()
                    push("ringing", 0)
                } else if (num.isNotBlank() && num != number) {
                    number = num
                    push("ringing", 0)
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (prev == TelephonyManager.EXTRA_STATE_IDLE) {
                    key = UUID.randomUUID().toString(); dir = "out"
                    val d = RemoteDesk.prefs(c)
                    number = d.getString("dialing", "") ?: ""
                    d.edit().putString("dialing", "").apply()
                }
                if (prev != st) {
                    connected = true; startMs = now
                    push("active", 0)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (prev != st && key.isNotEmpty()) {
                    val secs = if (connected) ((now - startMs) / 1000).toInt() else 0
                    push(if (!connected && dir == "in") "missed" else "ended", secs)
                    key = ""
                }
            }
        }
        p.edit().putString("state", st).putString("key", key).putString("number", number)
            .putString("dir", dir).putLong("start", startMs).putBoolean("connected", connected).apply()
    }
}
