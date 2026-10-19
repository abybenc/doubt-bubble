package com.abybencent.doubtbubble

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible screen opened from the notification or Quick Settings tile.
 * Opening an activity makes Android close the notification shade, then we pop the bubble and vanish.
 */
class ShowBubbleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BubbleService.isRunning) {
            startService(Intent(this, BubbleService::class.java).setAction(BubbleService.ACTION_SHOW))
        } else {
            // Bubble service isn't on (e.g. phone restarted): open setup so they can turn it on.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
