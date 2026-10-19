package com.abybencent.doubtbubble

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible screen opened from the notification or Quick Settings tile.
 * Opening an activity makes Android close the notification shade, then we pop the bubble and vanish.
 */
class ShowBubbleActivity : Activity() {

    companion object {
        const val ACTION_TURN_OFF = "com.abybencent.doubtbubble.TURN_OFF"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            intent?.action == ACTION_TURN_OFF -> BubbleService.turnOff()
            BubbleService.isRunning -> BubbleService.showBubble()
            // Not switched on (e.g. Accessibility toggle got reset): open setup.
            else -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
