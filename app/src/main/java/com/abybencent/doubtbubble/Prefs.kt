package com.abybencent.doubtbubble

import android.content.Context

object Prefs {
    private const val FILE = "settings"
    const val DEFAULT_MODEL = "gemini-flash-latest"

    fun apiKey(c: Context): String =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("api_key", "") ?: ""

    fun model(c: Context): String =
        (c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("model", DEFAULT_MODEL) ?: "")
            .ifBlank { DEFAULT_MODEL }

    fun save(c: Context, apiKey: String, model: String) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("api_key", apiKey.trim())
            .putString("model", model.trim().ifBlank { DEFAULT_MODEL })
            .apply()
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
