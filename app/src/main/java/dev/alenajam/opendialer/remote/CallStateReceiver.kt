package dev.alenajam.opendialer.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Wakes the app only when a call starts, changes or ends, then goes back to sleep.
 * This replaces a service that ran all day, which keeps battery use low.
 */
class CallStateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (!RemoteDesk.isPaired(context)) return
        if (intent.action == "dev.alenajam.opendialer.TELECOM_CALL_DETAILS") {
            CallTracker.enrichNumber(context, intent.getStringExtra("number").orEmpty())
        } else {
            val st = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            CallTracker.onPhone(context, st, intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty())
        }
        val pending = goAsync()
        Thread {
            try { RemoteDesk.flush(context) } finally { pending.finish() }
        }.start()
    }
}
