package com.kayx.manager.data.apk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** Receives the asynchronous PackageInstaller result and forwards the system confirmation UI. */
class ApkInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkBundleRepository.INSTALL_RESULT_ACTION) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
            }
            confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { runCatching { context.startActivity(it) } }
            return
        }

        val result = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "APK installation successful"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation blocked by system policy"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "Package conflict: $message"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "APK is incompatible: $message"
            PackageInstaller.STATUS_FAILURE_INVALID -> "Invalid APK: $message"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage: $message"
            else -> "Installation failed: $message"
        }
        Toast.makeText(context, result, Toast.LENGTH_LONG).show()
    }
}
