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

    // ─── Active API Settings (the one currently in use) ──────────
    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value).apply()

    var baseUrl: String
        get() = prefs.getString("base_url", "https://openrouter.ai/api/v1") ?: "https://openrouter.ai/api/v1"
        set(value) = prefs.edit().putString("base_url", value).apply()

    var model: String
        get() = prefs.getString("model", "openai/gpt-4o") ?: "openai/gpt-4o"
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

    // ─── Provider Preset ────────────────────────────────────────
    var providerPreset: String
        get() = prefs.getString("provider_preset", "openrouter") ?: "openrouter"
        set(value) = prefs.edit().putString("provider_preset", value).apply()

    // ─── OpenRouter API ─────────────────────────────────────────
    var openrouterApiKey: String
        get() = prefs.getString("openrouter_api_key", "") ?: ""
        set(value) = prefs.edit().putString("openrouter_api_key", value).apply()

    var openrouterModel: String
        get() = prefs.getString("openrouter_model", "openai/gpt-4o") ?: "openai/gpt-4o"
        set(value) = prefs.edit().putString("openrouter_model", value).apply()

    // ─── NVIDIA NIM API ─────────────────────────────────────────
    var nimApiKey: String
        get() = prefs.getString("nim_api_key", "") ?: ""
        set(value) = prefs.edit().putString("nim_api_key", value).apply()

    var nimModel: String
        get() = prefs.getString("nim_model", "meta/llama-3.1-8b-instruct") ?: "meta/llama-3.1-8b-instruct"
        set(value) = prefs.edit().putString("nim_model", value).apply()

    // ─── DeepSeek API ───────────────────────────────────────────
    var deepseekApiKey: String
        get() = prefs.getString("deepseek_api_key", "") ?: ""
        set(value) = prefs.edit().putString("deepseek_api_key", value).apply()

    var deepseekModel: String
        get() = prefs.getString("deepseek_model", "deepseek-chat") ?: "deepseek-chat"
        set(value) = prefs.edit().putString("deepseek_model", value).apply()

    // ─── OpenAI API ─────────────────────────────────────────────
    var openaiApiKey: String
        get() = prefs.getString("openai_api_key", "") ?: ""
        set(value) = prefs.edit().putString("openai_api_key", value).apply()

    var openaiModel: String
        get() = prefs.getString("openai_model", "gpt-4o") ?: "gpt-4o"
        set(value) = prefs.edit().putString("openai_model", value).apply()

    // ─── VLM (Vision) Settings ──────────────────────────────────
    var vlmEnabled: Boolean
        get() = prefs.getBoolean("vlm_enabled", false)
        set(value) = prefs.edit().putBoolean("vlm_enabled", value).apply()

    var vlmModel: String
        get() = prefs.getString("vlm_model", "openai/gpt-4o") ?: "openai/gpt-4o"
        set(value) = prefs.edit().putString("vlm_model", value).apply()

    // ─── Shizuku Settings ───────────────────────────────────────
    var shizukuEnabled: Boolean
        get() = prefs.getBoolean("shizuku_enabled", false)
        set(value) = prefs.edit().putBoolean("shizuku_enabled", value).apply()

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

    /**
     * Apply a provider preset, setting the active API key, URL, and model
     * from the saved values for that provider.
     */
    fun applyProviderPreset(preset: String) {
        providerPreset = preset
        when (preset) {
            "openrouter" -> {
                if (openrouterApiKey.isNotBlank()) apiKey = openrouterApiKey
                baseUrl = "https://openrouter.ai/api/v1"
                if (openrouterModel.isNotBlank()) model = openrouterModel
            }
            "nvidia_nim" -> {
                if (nimApiKey.isNotBlank()) apiKey = nimApiKey
                baseUrl = "https://integrate.api.nvidia.com/v1"
                if (nimModel.isNotBlank()) model = nimModel
            }
            "deepseek" -> {
                if (deepseekApiKey.isNotBlank()) apiKey = deepseekApiKey
                baseUrl = "https://api.deepseek.com/v1"
                if (deepseekModel.isNotBlank()) model = deepseekModel
            }
            "openai" -> {
                if (openaiApiKey.isNotBlank()) apiKey = openaiApiKey
                baseUrl = "https://api.openai.com/v1"
                if (openaiModel.isNotBlank()) model = openaiModel
            }
        }
    }

    fun isConfigured(): Boolean = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
}
