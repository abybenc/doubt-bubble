package com.abybencent.doubtbubble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After the phone restarts (or the app updates), put the "Got a doubt?" notification back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                if (Notifier.isEnabled(context)) Notifier.post(context)
            }
        }
    }
}
