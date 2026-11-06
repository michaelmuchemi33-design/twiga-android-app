package dev.alenajam.opendialer.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageInstaller
import android.os.Build
import android.telephony.TelephonyManager
import dev.alenajam.opendialer.remote.CallTracker
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks the GitHub release for a newer build and installs it with one tap (or silently when Android allows). */
object UpdateManager {
    private const val API = "https://github.com/michaelmuchemi33-design/twiga-android-app/releases/download/latest-apk/version.json"

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
            if (conn.responseCode != 200) return null
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = j.optLong("build", 0L)
            val url = j.optString("apk")
            return if (build > 0 && url.isNotBlank() && build > installedBuild(c)) Info(build, url, j.optLong("size", 0L)) else null
        } finally {
            conn.disconnect()
        }
    }

    /** Looks for an update at most every 6 hours and installs it by itself when it is safe to do so. */
    fun autoCheck(c: Context) {
        val sp = prefs(c)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("last", 0L) < 6 * 3600_000L) return
        Thread {
            val info = runCatching { check(c) }.getOrNull()
            if (info == null) {
                sp.edit().putLong("last", now).apply()
                return@Thread
            }
            if (!autoEnabled(c)) {
                sp.edit().putLong("last", now).apply()
                return@Thread
            }
            // Never interrupt an active cellular call. Retry on the next app start.
            if (CallTracker.state(c) != TelephonyManager.EXTRA_STATE_IDLE) return@Thread
            if (!c.packageManager.canRequestPackageInstalls()) {
                val lastNotice = sp.getLong("permissionNoticeAt", 0L)
                if (now - lastNotice > 24 * 3600_000L) {
                    sp.edit().putLong("permissionNoticeAt", now).apply()
                    notifyInstallPermissionRequired(c, info)
                }
                // Do not consume the six-hour check window; retry after permission is granted.
                return@Thread
            }
            sp.edit().putLong("last", now).apply()
            runCatching { install(c, info) { } }
        }.start()
    }

    private fun notifyInstallPermissionRequired(c: Context, info: Info) {
        val manager = c.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("twiga_update", "TWIGA updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val settings = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${c.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            c, 91, settings,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(c, "twiga_update")
            .setContentTitle("TWIGA update available")
            .setContentText("Allow TWIGA to install updates, then reopen the app.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(91, notification)
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
