package com.abybencent.doubtbubble

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup screen: save the Gemini key, grant permissions, start/stop the bubble. */
class MainActivity : Activity() {

    companion object {
        private const val REQ_NOTIF = 43
        private const val ACCENT = "#3D5AFE"
    }

    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var status: TextView
    private lateinit var startBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
        }
        val scrollRoot = ScrollView(this).apply { addView(col) }
        setContentView(scrollRoot)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 15 draws edge-to-edge: keep content clear of the status bar and nav buttons.
            scrollRoot.setOnApplyWindowInsetsListener { _, ins ->
                val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                col.setPadding(dp(22), bars.top + dp(20), dp(22), bars.bottom + dp(28))
                ins
            }
        }

        col.addView(text("Doubt Bubble", 28f, bold = true))
        col.addView(text(
            "Reading something and got a doubt? Pull down your notifications, tap \"Got a doubt?\", " +
                "then tap the ? bubble. It reads your screen and you can ask Gemini about it right there.",
            15f, color = "#555A6B", top = 6
        ))

        col.addView(text("1. Gemini API key", 17f, bold = true, top = 26))
        col.addView(text("Free from Google AI Studio. Tap below, sign in, create a key, copy it.", 14f, color = "#555A6B", top = 4))
        col.addView(button("Get a free key", outline = true) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
        })
        keyInput = field("Paste API key here", Prefs.apiKey(this), password = true)
        col.addView(keyInput)

        col.addView(text("Model (leave as is unless it stops working)", 13f, color = "#555A6B", top = 12))
        modelInput = field(Prefs.DEFAULT_MODEL, Prefs.model(this), password = false)
        col.addView(modelInput)

        col.addView(button("Save") {
            Prefs.save(this, keyInput.text.toString(), modelInput.text.toString())
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            refresh()
        })

        col.addView(text("2. Turn it on (once)", 17f, bold = true, top = 28))
        col.addView(text(
            "Doubt Bubble uses Android's Accessibility permission so it can read exactly what's on your screen " +
                "when you tap the bubble. Nothing is read at any other time.\n\n" +
                "Tap Turn on → find Doubt Bubble (often under \"Downloaded apps\" or \"Installed apps\") → switch it on.",
            14f, color = "#555A6B", top = 4
        ))
        startBtn = button("Turn on") { startFlow() }
        col.addView(startBtn)
        col.addView(text(
            "Switch greyed out or says \"Restricted setting\"? Android does that for apps installed outside the " +
                "Play Store. Tap below → ⋮ (top right) → Allow restricted settings, then try Turn on again.",
            13f, color = "#555A6B", top = 12
        ))
        col.addView(button("Fix greyed-out switch", outline = true) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        col.addView(button("Turn off", outline = true) {
            if (BubbleService.isRunning) BubbleService.turnOff()
            else Toast.makeText(this, "It's already off", Toast.LENGTH_SHORT).show()
            status.postDelayed({ refresh() }, 300)
        })
        status = text("", 14f, color = "#555A6B", top = 14)
        col.addView(status)

        col.addView(text("Tips", 17f, bold = true, top = 28))
        col.addView(text(
            "• The bubble stays hidden until you need it. Summon it from the \"Got a doubt?\" notification.\n" +
                "• Even faster: edit your Quick Settings and add the \"Ask a doubt\" tile.\n" +
                "• Done with it? Drag the bubble down onto the ✕ to throw it away.\n" +
                "• Drag it anywhere else and it sticks to the nearest edge.\n" +
                "• Tap the small screenshot in the chat to see what was captured.\n" +
                "• Apps that block screenshots (banking, some news/streaming apps) still work: it reads their text instead.\n" +
                "• The notification comes back by itself after you restart your phone. If it doesn't, " +
                "set Doubt Bubble's Battery to \"Unrestricted\" (Fix greyed-out switch → Battery).\n" +
                "• To switch everything off, tap Turn off in the notification.",
            14f, color = "#555A6B", top = 4
        ))
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val parts = mutableListOf<String>()
        parts += if (Prefs.apiKey(this).isBlank()) "API key: missing" else "API key: saved"
        parts += if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) "Notifications: allowed" else "Notifications: not allowed (needed to summon the bubble)"
        parts += if (BubbleService.isRunning) "Doubt Bubble: ON (waiting in your notifications)" else "Doubt Bubble: off"
        status.text = parts.joinToString("\n")
        startBtn.text = if (BubbleService.isRunning) "It's on ✓" else "Turn on"
    }

    private fun startFlow() {
        Prefs.save(this, keyInput.text.toString(), modelInput.text.toString())
        if (Prefs.apiKey(this).isBlank()) {
            Toast.makeText(this, "Paste your Gemini API key first", Toast.LENGTH_SHORT).show()
            return
        }
        if (BubbleService.isRunning) {
            Toast.makeText(this, "Already on. Pull down notifications to summon the bubble", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return // continues in onRequestPermissionsResult
        }
        openAccessibilitySettings()
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "Find Doubt Bubble and switch it on", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) openAccessibilitySettings()
    }

    // ---------- tiny view helpers ----------

    private fun text(s: String, size: Float, bold: Boolean = false, color: String = "#1B1D28", top: Int = 0) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
        }

    private fun field(hintText: String, value: String, password: Boolean) = EditText(this).apply {
        hint = hintText
        setText(value)
        isSingleLine = true
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F1F2F7")); cornerRadius = dp(12).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun button(label: String, outline: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(if (outline) Color.parseColor(ACCENT) else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            if (outline) setStroke(dp(2), Color.parseColor(ACCENT)) else setColor(Color.parseColor(ACCENT))
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(10) }
    }
}
