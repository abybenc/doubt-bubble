package com.abybencent.doubtbubble

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal Gemini REST client (no extra libraries). Call from a background thread. */
object GeminiClient {

    const val SYSTEM_PROMPT =
        "You are a friendly study buddy living in a floating bubble on the user's Android phone. " +
            "The first message includes a screenshot of whatever the user was reading when they had a doubt. " +
            "Answer their question about what is on that screen. Use simple, clear English and everyday examples. " +
            "Keep answers short (a few sentences or a short list) unless they ask for more detail. " +
            "If the screenshot is unclear or does not contain what they ask about, say so and answer from general knowledge."

    fun userText(text: String): JSONObject =
        JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    fun userTextWithImage(text: String, jpegBase64: String): JSONObject =
        JSONObject().put("role", "user").put(
            "parts", JSONArray()
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", jpegBase64)))
                .put(JSONObject().put("text", text))
        )

    fun modelText(text: String): JSONObject =
        JSONObject().put("role", "model")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

    @Throws(Exception::class)
    fun generate(apiKey: String, model: String, contents: JSONArray): String {
        val safeModel = URLEncoder.encode(model.removePrefix("models/"), "UTF-8")
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
            .put("contents", contents)

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }
                    .getOrDefault(raw.take(300))
                throw Exception(friendlyError(code, msg))
            }

            val json = JSONObject(raw)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                val reason = json.optJSONObject("promptFeedback")?.optString("blockReason") ?: "no answer"
                throw Exception("Gemini didn't answer ($reason). Try rephrasing.")
            }
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                ?: throw Exception("Empty answer from Gemini. Try again.")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.optBoolean("thought", false)) continue
                sb.append(p.optString("text", ""))
            }
            return sb.toString().trim().ifEmpty { throw Exception("Empty answer from Gemini. Try again.") }
        } finally {
            conn.disconnect()
        }
    }

    private fun friendlyError(code: Int, msg: String): String = when (code) {
        400 -> if (msg.contains("API key", true)) "Your Gemini API key looks wrong. Fix it in the app settings." else "Gemini error: $msg"
        401, 403 -> "Gemini rejected the API key. Check it in the app settings."
        404 -> "Model not found. Change the model name in settings. ($msg)"
        429 -> "Gemini rate limit hit. Wait a minute and try again."
        else -> "Gemini error $code: $msg"
    }
}
