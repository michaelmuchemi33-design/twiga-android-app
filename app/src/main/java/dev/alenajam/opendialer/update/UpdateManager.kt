package dev.alenajam.opendialer.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.telephony.TelephonyManager
import dev.alenajam.opendialer.remote.CallTracker
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks the GitHub release for a newer build and installs it with one tap (or silently when Android allows). */
object UpdateManager {
    private const val API = "https://api.github.com/repos/michaelmuchemi33-design/unity-ai-receptionist-app/releases/tags/latest-apk"

    data class Info(val build: Long, val url: String, val size: Long)

    private fun prefs(c: Context) = c.getSharedPreferences("twiga_update", Context.MODE_PRIVATE)
    fun autoEnabled(c: Context): Boolean = prefs(c).getBoolean("auto", true)
    fun setAuto(c: Context, on: Boolean) { prefs(c).edit().putBoolean("auto", on).apply() }

    fun installedBuild(c: Context): Long = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode

    /** Returns the newer build if one is published, otherwise null. */
    fun check(c: Context): Info? {
        val conn = URL(API).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = Regex("build: (\\d+)").find(j.optString("body"))?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val assets = j.optJSONArray("assets") ?: return null
            var url = ""
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); size = a.optLong("size") }
            }
            return if (url.isNotBlank() && build > installedBuild(c)) Info(build, url, size) else null
        } finally {
            conn.disconnect()
        }
    }

    /** Looks for an update at most every 6 hours and installs it by itself when it is safe to do so. */
    fun autoCheck(c: Context) {
        val sp = prefs(c)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("last", 0L) < 6 * 3600_000L) return
        sp.edit().putLong("last", now).apply()
        Thread {
            val info = runCatching { check(c) }.getOrNull() ?: return@Thread
            if (!autoEnabled(c)) return@Thread
            if (CallTracker.state(c) != TelephonyManager.EXTRA_STATE_IDLE) return@Thread
            if (!c.packageManager.canRequestPackageInstalls()) return@Thread
            runCatching { install(c, info) { } }
        }.start()
    }

    /** Downloads the APK and hands it to Android's installer. */
    fun install(c: Context, info: Info, onProgress: (Int) -> Unit) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            val conn = URL(info.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.size
            conn.inputStream.use { input ->
                session.openWrite("update.apk", 0, total).use { out ->
                    val buf = ByteArray(65536)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(c, id, Intent(c, InstallResultReceiver::class.java), flags)
            session.commit(pi.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }
}

/** Receives Android's answer. When it needs a tap, it opens the install prompt and also posts a notification. */
class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    private fun confirmIntent(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else i.getParcelableExtra(Intent.EXTRA_INTENT)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) != PackageInstaller.STATUS_PENDING_USER_ACTION) return
        val confirm = confirmIntent(intent) ?: return
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(confirm) }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("twiga_update", "TWIGA updates", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(context, 7, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            7,
            Notification.Builder(context, "twiga_update")
                .setContentTitle("TWIGA update ready")
                .setContentText("Tap to install the new version.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }
}
