package com.abybencent.doubtbubble

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/**
 * Keeps the floating "?" bubble on screen and a live screen-capture session.
 * Tap the bubble -> it hides itself, grabs the current screen, opens the chat panel.
 */
class BubbleService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "com.abybencent.doubtbubble.STOP"
        const val ACTION_SHOW = "com.abybencent.doubtbubble.SHOW"
        private const val CHANNEL_ID = "bubble"
        private const val NOTIF_ID = 1
        private const val MAX_IMAGE_SIDE = 1600

        @Volatile
        var isRunning = false
            private set

        fun showBubble(context: Context) {
            if (!isRunning) return
            context.startService(Intent(context, BubbleService::class.java).setAction(ACTION_SHOW))
        }
    }

    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var bgThread: HandlerThread
    private lateinit var bgHandler: Handler

    private var bubble: TextView? = null
    private lateinit var params: WindowManager.LayoutParams

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var latestImage: Image? = null // only touched on bgThread
    private var capW = 0
    private var capH = 0
    private var capturing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bgThread = HandlerThread("capture").also { it.start() }
        bgHandler = Handler(bgThread.looper)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW -> {
                capturing = false
                showBubble()
                return START_NOT_STICKY
            }
        }

        // Must be a foreground service of type mediaProjection BEFORE getMediaProjection().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        if (projection == null) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
            }
            if (data == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, data)
            if (mp == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // User stopped sharing from the status bar / system UI.
                    mainHandler.post { stopSelf() }
                }
            }, mainHandler)
            startCapture()
            createBubble() // created hidden; summon it from the notification or Quick Settings tile
            isRunning = true
        }
        return START_NOT_STICKY
    }

    // ---------- Screen capture ----------

    private fun screenSize(): Triple<Int, Int, Int> {
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, dpi)
        }
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null }
            if (img != null) {
                latestImage?.close()
                latestImage = img
            }
        }, bgHandler)
        return reader
    }

    private fun startCapture() {
        val (w, h, dpi) = screenSize()
        capW = w; capH = h
        val reader = newReader(w, h)
        imageReader = reader
        virtualDisplay = projection?.createVirtualDisplay(
            "DoubtBubbleCapture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation: resize the capture so landscape screenshots aren't squashed.
        val (w, h, dpi) = screenSize()
        if (w == capW && h == capH) return
        val vd = virtualDisplay ?: return
        capW = w; capH = h
        val newReader = newReader(w, h)
        val old = imageReader
        vd.resize(w, h, dpi)
        vd.surface = newReader.surface
        imageReader = newReader
        bgHandler.post {
            latestImage?.close()
            latestImage = null
            old?.close()
        }
        // keep bubble on screen after rotation
        bubble?.takeIf { it.isAttachedToWindow }?.let {
            params.x = params.x.coerceIn(0, max(0, w - it.width))
            params.y = params.y.coerceIn(0, max(0, h - it.height))
            runCatching { wm.updateViewLayout(it, params) }
        }
    }

    private fun captureAndOpenChat() {
        if (capturing) return
        capturing = true
        bubble?.visibility = View.INVISIBLE
        // Give the screen a moment to redraw without the bubble, then grab the newest frame.
        bgHandler.postDelayed({
            val path = latestImage?.let { img ->
                runCatching { saveImage(img) }.getOrNull()
            }
            mainHandler.post { openChat(path) }
        }, 300)
    }

    private fun saveImage(img: Image): String {
        val plane = img.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val padded = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        var bmp = Bitmap.createBitmap(padded, 0, 0, img.width, img.height)
        if (bmp !== padded) padded.recycle()

        val longSide = max(bmp.width, bmp.height)
        if (longSide > MAX_IMAGE_SIDE) {
            val s = MAX_IMAGE_SIDE.toFloat() / longSide
            val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
            bmp.recycle()
            bmp = scaled
        }
        val file = File(cacheDir, "screen_${System.currentTimeMillis()}.jpg")
        cacheDir.listFiles { f -> f.name.startsWith("screen_") }?.forEach { it.delete() }
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        return file.absolutePath
    }

    private fun openChat(path: String?) {
        if (path == null) {
            Toast.makeText(this, "Couldn't capture the screen. You can still type your doubt.", Toast.LENGTH_SHORT).show()
        }
        val i = Intent(this, ChatActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ChatActivity.EXTRA_SCREENSHOT, path)
            .putExtra(ChatActivity.EXTRA_STAMP, System.currentTimeMillis())
        try {
            startActivity(i)
        } catch (e: Exception) {
            bubble?.visibility = View.VISIBLE
            capturing = false
            Toast.makeText(this, "Couldn't open chat: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Floating bubble ----------

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
        params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
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
                            trash?.animate()?.scaleX(if (near) 1.3f else 1f)?.scaleY(if (near) 1.3f else 1f)?.setDuration(120)?.start()
                            if (near) v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
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
        val (w, h, _) = screenSize()
        params.x = w - dp(56) - dp(4)
        params.y = h / 3
    }

    /** Put the bubble on screen (from the notification, the Quick Settings tile, or after the chat closes). */
    private fun showBubble() {
        val v = bubble ?: return
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

    /** Remove the bubble from the screen. The service keeps running, so it can be summoned again. */
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
        val (w, h, _) = screenSize()
        val targetX = if (params.x + v.width / 2 < w / 2) dp(4) else w - v.width - dp(4)
        params.y = params.y.coerceIn(dp(24), max(dp(24), h - v.height - dp(48)))
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
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(72)
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
        return dx * dx + dy * dy < dp(80).toFloat() * dp(80)
    }

    /** Pull the bubble onto the target so it "sticks", Messenger style. */
    private fun magnetToTrash(v: View) {
        val (cx, cy) = trashCenter() ?: return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        // offset between window params and real screen position (status bar, cutouts)
        val offX = loc[0] - params.x
        val offY = loc[1] - params.y
        params.x = (cx - v.width / 2f).toInt() - offX
        params.y = (cy - v.height / 2f).toInt() - offY
        wm.updateViewLayout(v, params)
    }

    // ---------- Notification ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Doubt Bubble", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Tap to bring up the doubt bubble"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), flags)
        // Goes through a tiny invisible activity so the notification shade closes before the bubble appears.
        val show = PendingIntent.getActivity(
            this, 2,
            Intent(this, ShowBubbleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Got a doubt?")
            .setContentText("Tap to bring up the bubble")
            .setContentIntent(show)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Show bubble", show).build())
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        hideTrash()
        bubble?.takeIf { it.isAttachedToWindow }?.let { runCatching { wm.removeView(it) } }
        bubble = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { projection?.stop() }
        projection = null
        val reader = imageReader
        imageReader = null
        bgHandler.post {
            latestImage?.close()
            latestImage = null
            reader?.close()
            bgThread.quitSafely()
        }
        super.onDestroy()
    }
}
