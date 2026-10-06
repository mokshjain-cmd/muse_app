package com.phonebridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Muse approvals can only be decided in the Muse app, so the phone shows a
 * heads-up notification that opens it.
 */
object ApprovalNotice {
    private const val CHANNEL = "approvals"
    private const val MUSE_PACKAGE = "com.facebook.aura"

    private fun museIntent(context: Context): Intent? =
        context.packageManager.getLaunchIntentForPackage(MUSE_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun show(context: Context, approvalId: String, title: String, question: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Muse approvals", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = museIntent(context)?.let {
            PendingIntent.getActivity(context, approvalId.hashCode(), it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Approve in Muse: $title")
            .setContentText(question.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(question))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .apply { if (open != null) setContentIntent(open) }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(approvalId.hashCode(), n) }
    }

    fun clear(context: Context, approvalId: String) {
        NotificationManagerCompat.from(context).cancel(approvalId.hashCode())
    }

    /**
     * Opens the Muse app. Android often blocks a background app from starting
     * another app without telling it, so the notification is shown again too.
     */
    fun openMuseApp(context: Context, approvalId: String) {
        museIntent(context)?.let { runCatching { context.startActivity(it) } }
        show(context, approvalId, "Muse", "Tap to open the Muse app and approve.")
    }
}
