package com.abybencent.doubtbubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** The "Got a doubt?" notification, shared by the service and the boot receiver. */
object Notifier {
    private const val CHANNEL_ID = "bubble"
    private const val NOTIF_ID = 1

    /** True if Doubt Bubble is switched on in Accessibility settings (even if the service hasn't started yet). */
    fun isEnabled(c: Context): Boolean {
        val me = ComponentName(c, BubbleService::class.java).flattenToString()
        val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun post(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Doubt Bubble", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Tap to bring up the doubt bubble"
                setShowBadge(false)
            }
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val show = PendingIntent.getActivity(
            c, 2,
            Intent(c, ShowBubbleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val off = PendingIntent.getActivity(
            c, 1,
            Intent(c, ShowBubbleActivity::class.java)
                .setAction(ShowBubbleActivity.ACTION_TURN_OFF)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val n = Notification.Builder(c, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Got a doubt?")
            .setContentText("Tap to bring up the bubble")
            .setContentIntent(show)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Show bubble", show).build())
            .addAction(Notification.Action.Builder(null, "Turn off", off).build())
            .build()
        runCatching { nm.notify(NOTIF_ID, n) }
    }

    fun cancel(c: Context) {
        c.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }
}
