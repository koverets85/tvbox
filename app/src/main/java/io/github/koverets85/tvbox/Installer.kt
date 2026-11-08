package io.github.koverets85.tvbox

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File

object Installer {
    /** Downloads the APK, reporting progress 0..100 (or -1 if unknown). Runs on a background thread. */
    fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File {
        val out = File(ctx.cacheDir, "download.apk")
        val c = Net.open(url)
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            var last = -2
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val p = if (total > 0) (done * 100 / total).toInt() else -1
                        if (p != last) { last = p; onProgress(p) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return out
    }

    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setSize(apk.length())
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app.apk", 0, apk.length()).use { os ->
                apk.inputStream().use { it.copyTo(os) }
                s.fsync(os)
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val intent = Intent(ctx, InstallReceiver::class.java)
            val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
            s.commit(pending.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(ctx, "Установлено", Toast.LENGTH_LONG).show()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "код $status"
                Toast.makeText(ctx, "Не установилось: $msg", Toast.LENGTH_LONG).show()
            }
        }
        ctx.sendBroadcast(Intent(MainActivity.ACTION_REFRESH).setPackage(ctx.packageName))
    }
}
