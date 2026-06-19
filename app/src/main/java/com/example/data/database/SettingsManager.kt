package com.example.data.database

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("api_keys_prefs", Context.MODE_PRIVATE)

    var twelveApiKey: String
        get() = prefs.getString("TWELVE_API_KEY", "") ?: ""
        set(value) = prefs.edit().putString("TWELVE_API_KEY", value).apply()

    var deepseekApiKey: String
        get() = prefs.getString("DEEPSEEK_API_KEY", "") ?: ""
        set(value) = prefs.edit().putString("DEEPSEEK_API_KEY", value).apply()

    fun areKeysSet(): Boolean {
        return twelveApiKey.isNotBlank()
    }
}
