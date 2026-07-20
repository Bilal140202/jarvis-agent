package com.jarvis.agent.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import com.jarvis.agent.R
import com.jarvis.agent.llm.LlmClient
import com.jarvis.agent.service.AgentAccessibilityService
import com.jarvis.agent.service.ShizukuService
import com.jarvis.agent.util.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsActivity : Activity() {

    private val llmClient = LlmClient()
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        loadSettings()
        setupListeners()
    }

    override fun onBackPressed() {
        finish()
    }

    private fun loadSettings() {
        findViewById<EditText>(R.id.inputOpenrouterKey).setText(Preferences.openrouterApiKey)
        findViewById<EditText>(R.id.inputOpenrouterModel).setText(Preferences.openrouterModel)
        findViewById<EditText>(R.id.inputNimKey).setText(Preferences.nimApiKey)
        findViewById<EditText>(R.id.inputNimModel).setText(Preferences.nimModel)
        findViewById<EditText>(R.id.inputDeepseekKey).setText(Preferences.deepseekApiKey)
        findViewById<EditText>(R.id.inputDeepseekModel).setText(Preferences.deepseekModel)
        findViewById<EditText>(R.id.inputOpenaiKey).setText(Preferences.openaiApiKey)
        findViewById<EditText>(R.id.inputOpenaiModel).setText(Preferences.openaiModel)
        findViewById<EditText>(R.id.inputApiKey).setText(Preferences.apiKey)
        findViewById<EditText>(R.id.inputBaseUrl).setText(Preferences.baseUrl)
        findViewById<EditText>(R.id.inputModel).setText(Preferences.model)

        findViewById<SeekBar>(R.id.seekMaxSteps).progress = Preferences.maxSteps
        findViewById<TextView>(R.id.labelMaxSteps).text = "Max Steps: ${Preferences.maxSteps}"
        findViewById<SeekBar>(R.id.seekTemperature).progress = (Preferences.temperature * 100).toInt()
        findViewById<TextView>(R.id.labelTemperature).text = "Temperature: ${Preferences.temperature}"
        findViewById<SeekBar>(R.id.seekMaxTokens).progress = Preferences.maxTokens
        findViewById<TextView>(R.id.labelMaxTokens).text = "Max Tokens: ${Preferences.maxTokens}"

        findViewById<Switch>(R.id.switchCompression).isChecked = Preferences.useScreenCompression
        findViewById<Switch>(R.id.switchSkillMemory).isChecked = Preferences.skillMemoryEnabled
        findViewById<Switch>(R.id.switchConfirmDestructive).isChecked = Preferences.confirmDestructive
        findViewById<Switch>(R.id.switchOverlay).isChecked = Preferences.overlayEnabled
        findViewById<Switch>(R.id.switchVoice).isChecked = Preferences.voiceEnabled
        findViewById<Switch>(R.id.switchVlm).isChecked = Preferences.vlmEnabled
        findViewById<Switch>(R.id.switchShizuku).isChecked = Preferences.shizukuEnabled
        findViewById<EditText>(R.id.inputVlmModel).setText(Preferences.vlmModel)
        findViewById<Switch>(R.id.switchTelegram).isChecked = Preferences.telegramEnabled
        findViewById<EditText>(R.id.inputTelegramToken).setText(Preferences.telegramBotToken)

        val a11yStatus = findViewById<TextView>(R.id.labelA11yStatus)
        a11yStatus.text = if (AgentAccessibilityService.isRunning()) "Active" else "Not Active"
        val shizukuStatus = findViewById<TextView>(R.id.labelShizukuStatus)
        shizukuStatus.text = if (ShizukuService().isAvailable()) "Shizuku Connected" else "Shizuku Not Available"
    }

    private fun setupListeners() {
        findViewById<Button>(R.id.btnApplyOpenrouter).setOnClickListener {
            saveAllProviderKeys()
            Preferences.applyProviderPreset("openrouter")
            refreshActiveFields()
            Toast.makeText(this, "Switched to OpenRouter", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnApplyNim).setOnClickListener {
            saveAllProviderKeys()
            Preferences.applyProviderPreset("nvidia_nim")
            refreshActiveFields()
            Toast.makeText(this, "Switched to NVIDIA NIM", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnApplyDeepseek).setOnClickListener {
            saveAllProviderKeys()
            Preferences.applyProviderPreset("deepseek")
            refreshActiveFields()
            Toast.makeText(this, "Switched to DeepSeek", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnApplyOpenai).setOnClickListener {
            saveAllProviderKeys()
            Preferences.applyProviderPreset("openai")
            refreshActiveFields()
            Toast.makeText(this, "Switched to OpenAI", Toast.LENGTH_SHORT).show()
        }

        findViewById<SeekBar>(R.id.seekMaxSteps).setOnSeekBarChangeListener(
            SimpleSeekListener { Preferences.maxSteps = it; findViewById<TextView>(R.id.labelMaxSteps).text = "Max Steps: $it" }
        )
        findViewById<SeekBar>(R.id.seekTemperature).setOnSeekBarChangeListener(
            SimpleSeekListener { val t = it/100f; Preferences.temperature = t; findViewById<TextView>(R.id.labelTemperature).text = "Temperature: $t" }
        )
        findViewById<SeekBar>(R.id.seekMaxTokens).setOnSeekBarChangeListener(
            SimpleSeekListener { Preferences.maxTokens = it; findViewById<TextView>(R.id.labelMaxTokens).text = "Max Tokens: $it" }
        )

        findViewById<Switch>(R.id.switchCompression).setOnCheckedChangeListener { _, v -> Preferences.useScreenCompression = v }
        findViewById<Switch>(R.id.switchSkillMemory).setOnCheckedChangeListener { _, v -> Preferences.skillMemoryEnabled = v }
        findViewById<Switch>(R.id.switchConfirmDestructive).setOnCheckedChangeListener { _, v -> Preferences.confirmDestructive = v }
        findViewById<Switch>(R.id.switchOverlay).setOnCheckedChangeListener { _, v -> Preferences.overlayEnabled = v }
        findViewById<Switch>(R.id.switchVoice).setOnCheckedChangeListener { _, v -> Preferences.voiceEnabled = v }
        findViewById<Switch>(R.id.switchVlm).setOnCheckedChangeListener { _, v -> Preferences.vlmEnabled = v }
        findViewById<Switch>(R.id.switchShizuku).setOnCheckedChangeListener { _, v -> Preferences.shizukuEnabled = v }
        findViewById<Switch>(R.id.switchTelegram).setOnCheckedChangeListener { _, v -> Preferences.telegramEnabled = v }

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnFetchModels).setOnClickListener { fetchModels() }
        findViewById<Button>(R.id.btnOpenA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun saveAllProviderKeys() {
        Preferences.openrouterApiKey = findViewById<EditText>(R.id.inputOpenrouterKey).text.toString().trim()
        Preferences.openrouterModel = findViewById<EditText>(R.id.inputOpenrouterModel).text.toString().trim()
        Preferences.nimApiKey = findViewById<EditText>(R.id.inputNimKey).text.toString().trim()
        Preferences.nimModel = findViewById<EditText>(R.id.inputNimModel).text.toString().trim()
        Preferences.deepseekApiKey = findViewById<EditText>(R.id.inputDeepseekKey).text.toString().trim()
        Preferences.deepseekModel = findViewById<EditText>(R.id.inputDeepseekModel).text.toString().trim()
        Preferences.openaiApiKey = findViewById<EditText>(R.id.inputOpenaiKey).text.toString().trim()
        Preferences.openaiModel = findViewById<EditText>(R.id.inputOpenaiModel).text.toString().trim()
    }

    private fun refreshActiveFields() {
        findViewById<EditText>(R.id.inputApiKey).setText(Preferences.apiKey)
        findViewById<EditText>(R.id.inputBaseUrl).setText(Preferences.baseUrl)
        findViewById<EditText>(R.id.inputModel).setText(Preferences.model)
    }

    private fun saveSettings() {
        saveAllProviderKeys()
        Preferences.apiKey = findViewById<EditText>(R.id.inputApiKey).text.toString().trim()
        Preferences.baseUrl = findViewById<EditText>(R.id.inputBaseUrl).text.toString().trim()
        Preferences.model = findViewById<EditText>(R.id.inputModel).text.toString().trim()
        Preferences.vlmModel = findViewById<EditText>(R.id.inputVlmModel).text.toString().trim()
        Preferences.telegramBotToken = findViewById<EditText>(R.id.inputTelegramToken).text.toString().trim()
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun fetchModels() {
        scope.launch {
            try {
                val models = llmClient.fetchAvailableModels()
                if (models.isEmpty()) { Toast.makeText(this@SettingsActivity, "No models found", Toast.LENGTH_SHORT).show(); return@launch }
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Select Model")
                    .setItems(models.toTypedArray()) { _, which -> findViewById<EditText>(R.id.inputModel).setText(models[which]) }
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private class SimpleSeekListener(private val onProgress: (Int) -> Unit) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onProgress(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
