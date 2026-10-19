package com.abybencent.doubtbubble

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * The heart of Doubt Bubble. Runs as an Accessibility service so it can:
 *  - float the "?" bubble over any app (no extra overlay permission needed),
 *  - take a screenshot of exactly what's on screen right now, and
 *  - read the on-screen text (works even when an app blocks screenshots).
 * The bubble is hidden until summoned from the notification or Quick Settings tile.
 */
class BubbleService : AccessibilityService() {

    companion object {
        private const val MAX_IMAGE_SIDE = 1600
        private const val MAX_TEXT_CHARS = 12000

        @Volatile
        var instance: BubbleService? = null
            private set

        val isRunning: Boolean get() = instance != null

        /** Called from the notification / tile / chat panel. Safe to call from the main thread. */
        fun showBubble() { instance?.showBubbleInternal() }

        fun turnOff() { instance?.disableSelf() }
    }

    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bubble: TextView? = null
    private lateinit var params: WindowManager.LayoutParams
    private var capturing = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        instance = this
        createBubble()
        Notifier.post(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* not needed */ }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance == null) return
        instance = null
        hideTrash()
        bubble?.takeIf { it.isAttachedToWindow }?.let { runCatching { wm.removeView(it) } }
        bubble = null
        Notifier.cancel(this)
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val (w, h) = screenSize()
        bubble?.takeIf { it.isAttachedToWindow }?.let {
            params.x = params.x.coerceIn(0, max(0, w - it.width))
            params.y = params.y.coerceIn(0, max(0, h - it.height))
            runCatching { wm.updateViewLayout(it, params) }
        }
    }

    // ---------- Capture: screenshot + on-screen text ----------

    private fun captureAndOpenChat() {
        if (capturing) return
        capturing = true
        bubble?.visibility = View.INVISIBLE
        // Let the screen redraw without the bubble, then grab it.
        mainHandler.postDelayed({
            val text = runCatching { collectScreenText() }.getOrDefault("")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        Thread {
                            val path = runCatching { saveScreenshot(result) }.getOrNull()
                            mainHandler.post { openChat(path, text) }
                        }.start()
                    }

                    override fun onFailure(errorCode: Int) {
                        openChat(null, text)
                    }
                })
            } else {
                openChat(null, text) // Android 10 and older: text only
            }
        }, 250)
    }

    private fun saveScreenshot(result: ScreenshotResult): String? {
        val hb = result.hardwareBuffer
        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
        var bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
        hw?.recycle()
        hb.close()
        if (bmp == null) return null

        val longSide = max(bmp.width, bmp.height)
        if (longSide > MAX_IMAGE_SIDE) {
            val s = MAX_IMAGE_SIDE.toFloat() / longSide
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
            bmp.recycle()
            bmp = scaled
        }
        val out: Bitmap = bmp
        cacheDir.listFiles { f -> f.name.startsWith("screen_") && f.name.endsWith(".jpg") }?.forEach { it.delete() }
        val file = File(cacheDir, "screen_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        out.recycle()
        return file.absolutePath
    }

    /** Reads the visible text of the app(s) on screen, skipping our own bubble and system UI. */
    private fun collectScreenText(): String {
        val sb = StringBuilder()
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNullTo(roots) { it.root }
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots += it }

        for (root in roots) {
            val pkg = root.packageName?.toString() ?: continue
            if (pkg == packageName) continue
            sb.append("[App: ").append(appLabel(pkg)).append("]\n")
            walk(root, sb, 0)
            if (sb.length >= MAX_TEXT_CHARS) break
        }
        return sb.toString().take(MAX_TEXT_CHARS).trim()
    }

    private fun walk(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 80 || sb.length >= MAX_TEXT_CHARS || !node.isVisibleToUser) return
        val t = (node.text ?: node.contentDescription)?.toString()?.trim()
        if (!t.isNullOrEmpty() && !sb.endsWith(t + "\n")) sb.append(t).append('\n')
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, sb, depth + 1)
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun openChat(screenshotPath: String?, screenText: String) {
        val textFile = File(cacheDir, "screen_text.txt")
        runCatching { textFile.writeText(screenText) }
        if (screenshotPath == null && screenText.isBlank()) {
            Toast.makeText(this, "Couldn't read this screen. You can still type your doubt.", Toast.LENGTH_SHORT).show()
        }
        val i = Intent(this, ChatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ChatActivity.EXTRA_SCREENSHOT, screenshotPath)
            .putExtra(ChatActivity.EXTRA_TEXT_FILE, textFile.absolutePath)
            .putExtra(ChatActivity.EXTRA_STAMP, System.currentTimeMillis())
        try {
            startActivity(i)
        } catch (e: Exception) {
            bubble?.visibility = View.VISIBLE
            Toast.makeText(this, "Couldn't open chat: ${e.message}", Toast.LENGTH_LONG).show()
        }
        capturing = false
    }

    // ---------- Floating bubble ----------

    private fun overlayParams(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    )

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        val size = dp(56)
        val view = TextView(this).apply {
            text = "?"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3D5AFE"))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(6).toFloat()
            alpha = 0.92f
            contentDescription = "Ask a doubt"
        }
        params = overlayParams(size, size, true).apply { gravity = Gravity.TOP or Gravity.START }
        resetPosition()

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false
        var overTrash = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    dragging = false; overTrash = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        showTrash()
                    }
                    if (dragging) {
                        val near = isNearTrash(e.rawX, e.rawY)
                        if (near != overTrash) {
                            overTrash = near
                            val s = if (near) 1.3f else 1f
                            trash?.animate()?.scaleX(s)?.scaleY(s)?.setDuration(120)?.start()
                            if (near) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        }
                        if (near) {
                            magnetToTrash(v)
                        } else {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            wm.updateViewLayout(v, params)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    if (dragging) {
                        hideTrash()
                        if (overTrash) throwAway(v) else snapToEdge(v)
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        captureAndOpenChat()
                    }
                }
            }
            true
        }
        bubble = view
    }

    private fun resetPosition() {
        val (w, h) = screenSize()
        params.x = w - dp(56) - dp(4)
        params.y = h / 3
    }

    private fun showBubbleInternal() {
        val v = bubble ?: return
        capturing = false
        v.animate().cancel()
        v.visibility = View.VISIBLE
        v.scaleX = 1f; v.scaleY = 1f; v.alpha = 0.92f
        if (!v.isAttachedToWindow) {
            resetPosition()
            runCatching { wm.addView(v, params) }
            v.scaleX = 0f; v.scaleY = 0f
            v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }
    }

    private fun hideBubble() {
        val v = bubble ?: return
        if (v.isAttachedToWindow) runCatching { wm.removeView(v) }
    }

    private fun throwAway(v: View) {
        v.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(180)
            .withEndAction { hideBubble() }
            .start()
    }

    private fun snapToEdge(v: View) {
        val (w, h) = screenSize()
        val targetX = if (params.x + v.width / 2 < w / 2) dp(4) else w - v.width - dp(4)
        params.y = params.y.coerceIn(dp(24), max(dp(24), h - v.height - dp(96)))
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 180
            addUpdateListener {
                params.x = it.animatedValue as Int
                if (v.isAttachedToWindow) wm.updateViewLayout(v, params)
            }
            start()
        }
    }

    // ---------- "Throw away" target (like Messenger) ----------

    private var trash: TextView? = null

    private fun showTrash() {
        if (trash != null) return
        val size = dp(64)
        val t = TextView(this).apply {
            text = "✕"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC1B1D28"))
                setStroke(dp(2), Color.WHITE)
            }
            alpha = 0f
            translationY = dp(40).toFloat()
        }
        val lp = overlayParams(size, size, false).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }
        runCatching { wm.addView(t, lp) }
        t.animate().alpha(1f).translationY(0f).setDuration(160).start()
        trash = t
    }

    private fun hideTrash() {
        val t = trash ?: return
        trash = null
        t.animate().alpha(0f).translationY(dp(40).toFloat()).setDuration(140)
            .withEndAction { runCatching { wm.removeView(t) } }
            .start()
    }

    private fun trashCenter(): Pair<Float, Float>? {
        val t = trash ?: return null
        if (!t.isAttachedToWindow || t.width == 0) return null
        val loc = IntArray(2)
        t.getLocationOnScreen(loc)
        return Pair(loc[0] + t.width / 2f, loc[1] + t.height / 2f)
    }

    private fun isNearTrash(x: Float, y: Float): Boolean {
        val (cx, cy) = trashCenter() ?: return false
        val dx = x - cx; val dy = y - cy
        val r = dp(80).toFloat()
        return dx * dx + dy * dy < r * r
    }

    private fun magnetToTrash(v: View) {
        val (cx, cy) = trashCenter() ?: return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val offX = loc[0] - params.x
        val offY = loc[1] - params.y
        params.x = (cx - v.width / 2f).toInt() - offX
        params.y = (cy - v.height / 2f).toInt() - offY
        wm.updateViewLayout(v, params)
    }
}
