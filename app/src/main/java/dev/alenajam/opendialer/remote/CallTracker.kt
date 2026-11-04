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
                    key = UUID.randomUUID().toString(); number = num; dir = "in"; startMs = now; connected = false
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
