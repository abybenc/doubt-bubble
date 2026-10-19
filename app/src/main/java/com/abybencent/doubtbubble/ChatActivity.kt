package com.abybencent.doubtbubble

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Html
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.io.File

/** Bottom-sheet style chat panel that opens on top of whatever app you were reading. */
class ChatActivity : Activity() {

    companion object {
        const val EXTRA_SCREENSHOT = "screenshot"
        const val EXTRA_STAMP = "stamp"
        private const val ACCENT = "#3D5AFE"
    }

    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var thumb: ImageView
    private lateinit var chips: LinearLayout

    private var screenshotB64: String? = null
    private var imageSent = false
    private val history = JSONArray()
    private var busy = false
    private var lastStamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        loadSession(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadSession(intent)
    }

    override fun onStop() {
        super.onStop()
        BubbleService.showBubble(this)
    }

    /** Each tap on the bubble = fresh screenshot = fresh conversation. */
    private fun loadSession(i: Intent) {
        val stamp = i.getLongExtra(EXTRA_STAMP, 0L)
        if (stamp == lastStamp && stamp != 0L) return
        lastStamp = stamp

        messages.removeAllViews()
        while (history.length() > 0) history.remove(history.length() - 1)
        imageSent = false
        screenshotB64 = null
        busy = false
        sendBtn.isEnabled = true
        chips.visibility = View.VISIBLE

        val path = i.getStringExtra(EXTRA_SCREENSHOT)
        if (path != null && File(path).exists()) {
            val bytes = File(path).readBytes()
            screenshotB64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            thumb.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            thumb.visibility = View.VISIBLE
        } else {
            thumb.visibility = View.GONE
        }

        if (Prefs.apiKey(this).isBlank()) {
            addBubble("Add your Gemini API key first. Open the Doubt Bubble app, paste the key, then come back.", false)
        } else {
            addBubble(
                if (screenshotB64 != null) "I can see your screen. What's your doubt?"
                else "Couldn't capture the screen this time, but ask away.", false
            )
        }
        input.setText("")
        input.requestFocus()
    }

    // ---------- UI ----------

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setOnClickListener { finish() } // tap outside the panel closes it
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(dp(22f), dp(22f), dp(22f), dp(22f), 0f, 0f, 0f, 0f)
            }
            isClickable = true // don't pass taps to root
        }
        val panelHeight = (resources.displayMetrics.heightPixels * 0.72).toInt()
        root.addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, panelHeight, Gravity.BOTTOM))

        // drag handle
        panel.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(Color.parseColor("#D0D3DC")); cornerRadius = dp(3f) }
        }, LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })

        // header: thumbnail + title + close
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setStroke(dp(1), Color.parseColor("#D0D3DC")); cornerRadius = dp(6f) }
            clipToOutline = true
            setOnClickListener {
                // tap thumbnail to toggle big preview
                val lp = layoutParams
                val big = lp.height == dp(52)
                lp.height = if (big) dp(260) else dp(52)
                lp.width = if (big) dp(130) else dp(30)
                layoutParams = lp
            }
        }
        header.addView(thumb, LinearLayout.LayoutParams(dp(30), dp(52)).apply { rightMargin = dp(12) })
        header.addView(TextView(this).apply {
            text = "Ask about this screen"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1B1D28"))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "✕"
            textSize = 20f
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setTextColor(Color.parseColor("#6B7080"))
            setOnClickListener { finish() }
        })
        panel.addView(header)

        // messages
        scroll = ScrollView(this).apply { isFillViewport = true }
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        scroll.addView(messages)
        panel.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // quick doubt chips
        chips = LinearLayout(this)
        listOf("Explain this simply", "Summarise this", "Define the hard words", "Give me an example").forEach { q ->
            chips.addView(TextView(this).apply {
                text = q
                textSize = 13f
                setTextColor(Color.parseColor(ACCENT))
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = GradientDrawable().apply {
                    setStroke(dp(1), Color.parseColor(ACCENT)); cornerRadius = dp(16f)
                }
                setOnClickListener { send(q) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
        panel.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })

        // input row
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Type your doubt…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F1F2F7")); cornerRadius = dp(22f) }
        }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        sendBtn = Button(this).apply {
            text = "Ask"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply { setColor(Color.parseColor(ACCENT)); cornerRadius = dp(22f) }
            setOnClickListener { send(input.text.toString()) }
        }
        row.addView(sendBtn, LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)).apply { leftMargin = dp(8) })
        panel.addView(row)

        setContentView(root)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun addBubble(text: String, fromUser: Boolean, isError: Boolean = false): TextView {
        val tv = TextView(this).apply {
            textSize = 15f
            setLineSpacing(0f, 1.15f)
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(if (fromUser) Color.WHITE else Color.parseColor(if (isError) "#B3261E" else "#1B1D28"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(if (fromUser) ACCENT else if (isError) "#FDECEA" else "#F1F2F7"))
                cornerRadius = dp(16f)
            }
            setText(if (fromUser) text else renderMarkdown(text))
        }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = dp(6)
            if (fromUser) leftMargin = dp(48) else rightMargin = dp(24)
        }
        messages.addView(tv, lp)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return tv
    }

    // ---------- Asking Gemini ----------

    private fun send(raw: String) {
        val q = raw.trim()
        if (q.isEmpty() || busy) return
        val key = Prefs.apiKey(this)
        if (key.isBlank()) {
            addBubble("No API key yet. Open the Doubt Bubble app and add your Gemini key.", false, true)
            return
        }
        busy = true
        sendBtn.isEnabled = false
        chips.visibility = View.GONE
        input.setText("")
        addBubble(q, true)
        val thinking = addBubble("Thinking…", false)

        val img = screenshotB64
        val withImage = !imageSent && img != null
        val turn = if (withImage) GeminiClient.userTextWithImage(q, img!!) else GeminiClient.userText(q)
        history.put(turn)
        val model = Prefs.model(this)
        val session = lastStamp
        val snapshot = JSONArray(history.toString())

        Thread {
            val result = runCatching { GeminiClient.generate(key, model, snapshot) }
            runOnUiThread {
                if (isDestroyed || session != lastStamp) return@runOnUiThread
                messages.removeView(thinking)
                result.onSuccess { answer ->
                    if (withImage) imageSent = true
                    history.put(GeminiClient.modelText(answer))
                    addBubble(answer, false)
                }.onFailure { e ->
                    history.remove(history.length() - 1) // drop the failed question so you can retry
                    addBubble(e.message ?: "Something went wrong. Check your internet and try again.", false, true)
                }
                busy = false
                sendBtn.isEnabled = true
            }
        }.start()
    }

    /** Tiny markdown renderer: **bold**, *italic*, `code`, headings and bullet lists. */
    private fun renderMarkdown(md: String): CharSequence {
        val html = StringBuilder()
        md.lines().forEach { line0 ->
            var line = Html.escapeHtml(line0)
            val trimmed = line.trimStart()
            line = when {
                trimmed.startsWith("### ") || trimmed.startsWith("## ") || trimmed.startsWith("# ") ->
                    "<b>" + trimmed.trimStart('#', ' ') + "</b>"
                trimmed.startsWith("* ") || trimmed.startsWith("- ") ->
                    "&nbsp;&nbsp;• " + trimmed.substring(2)
                else -> line
            }
            line = line.replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
                .replace(Regex("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])"), "<i>$1</i>")
                .replace(Regex("`([^`]+)`"), "<tt>$1</tt>")
            html.append(line).append("<br>")
        }
        return Html.fromHtml(html.toString().removeSuffix("<br>"), Html.FROM_HTML_MODE_COMPACT)
    }
}
