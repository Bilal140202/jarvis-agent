package com.jarvis.agent.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized preferences manager for all Jarvis Agent settings.
 * Wraps SharedPreferences with type-safe accessors.
 */
object Preferences {

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
    }

    // ─── API Settings ───────────────────────────────────────────
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value).apply()

    var baseUrl: String
        get() = prefs.getString("base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(value) = prefs.edit().putString("base_url", value).apply()

    var model: String
        get() = prefs.getString("model", "gpt-4o") ?: "gpt-4o"
        set(value) = prefs.edit().putString("model", value).apply()

    var maxSteps: Int
        get() = prefs.getInt("max_steps", 25)
        set(value) = prefs.edit().putInt("max_steps", value).apply()

    var temperature: Float
        get() = prefs.getFloat("temperature", 0.3f)
        set(value) = prefs.edit().putFloat("temperature", value).apply()

    var maxTokens: Int
        get() = prefs.getInt("max_tokens", 2048)
        set(value) = prefs.edit().putInt("max_tokens", value).apply()

    var useScreenCompression: Boolean
        get() = prefs.getBoolean("screen_compression", true)
        set(value) = prefs.edit().putBoolean("screen_compression", value).apply()

    // ─── Telegram Settings ──────────────────────────────────────
    var telegramBotToken: String
        get() = prefs.getString("telegram_bot_token", "") ?: ""
        set(value) = prefs.edit().putString("telegram_bot_token", value).apply()

    var telegramEnabled: Boolean
        get() = prefs.getBoolean("telegram_enabled", false)
        set(value) = prefs.edit().putBoolean("telegram_enabled", value).apply()

    // ─── Voice Settings ────────────────────────────────────────
    var voiceEnabled: Boolean
        get() = prefs.getBoolean("voice_enabled", true)
        set(value) = prefs.edit().putBoolean("voice_enabled", value).apply()

    // ─── Overlay Settings ───────────────────────────────────────
    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay_enabled", true)
        set(value) = prefs.edit().putBoolean("overlay_enabled", value).apply()

    // ─── Onboarding ────────────────────────────────────────────
    var onboardingCompleted: Boolean
        get() = prefs.getBoolean("onboarding_completed", false)
        set(value) = prefs.edit().putBoolean("onboarding_completed", value).apply()

    // ─── Safety ─────────────────────────────────────────────────
    var confirmDestructive: Boolean
        get() = prefs.getBoolean("confirm_destructive", true)
        set(value) = prefs.edit().putBoolean("confirm_destructive", value).apply()

    // ─── Skill Memory ──────────────────────────────────────────
    var skillMemoryEnabled: Boolean
        get() = prefs.getBoolean("skill_memory_enabled", true)
        set(value) = prefs.edit().putBoolean("skill_memory_enabled", value).apply()

    // ─── Provider Preset ────────────────────────────────────────
    var providerPreset: String
        get() = prefs.getString("provider_preset", "custom") ?: "custom"
        set(value) = prefs.edit().putString("provider_preset", value).apply()

    fun isConfigured(): Boolean = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}
