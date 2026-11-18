package io.github.r4t2.vord

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/** Installs the patched APKs as one session; Android asks the user to confirm. */
object Installer {
    /** Set by the UI while an install is running. Called on an arbitrary thread. */
    @Volatile var listener: ((ok: Boolean, message: String) -> Unit)? = null

    fun install(ctx: Context, files: List<File>) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(Sources.PATCHED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            for (f in files) {
                f.inputStream().use { input ->
                    session.openWrite(f.name, 0, f.length()).use { out ->
                        input.copyTo(out, 1 shl 20)
                        session.fsync(out)
                    }
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction("io.github.r4t2.vord.INSTALL_RESULT")
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) ctx.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Installer.listener?.invoke(true, "Installed")
            else -> Installer.listener?.invoke(false, "Install failed ($status): $message")
        }
    }
}
