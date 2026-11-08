package dev.alenajam.opendialer.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Private in-app bridge for caller ID obtained from Telecom's InCallService. */
class TelecomCallDetailsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!RemoteDesk.isPaired(context)) return
        CallTracker.enrichNumber(context, intent.getStringExtra("number").orEmpty())
        val pending = goAsync()
        Thread {
            try { RemoteDesk.flush(context) } finally { pending.finish() }
        }.start()
    }
}
