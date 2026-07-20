package com.jarvis.agent.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.agent.R
import com.jarvis.agent.llm.LlmClient
import com.jarvis.agent.service.AgentAccessibilityService
import com.jarvis.agent.util.Preferences
import kotlinx.coroutines.launch

/**
 * Settings Activity — configure API, agent behavior, and services.
 */
class SettingsActivity : AppCompatActivity() {

    private val llmClient = LlmClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        loadSettings()
        setupListeners()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadSettings() {
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
        findViewById<Switch>(R.id.switchTelegram).isChecked = Preferences.telegramEnabled

        findViewById<EditText>(R.id.inputTelegramToken).setText(Preferences.telegramBotToken)

        // Accessibility status
        val a11yStatus = findViewById<TextView>(R.id.labelA11yStatus)
        a11yStatus.text = if (AgentAccessibilityService.isRunning()) "✓ Active" else "✗ Not Active"
    }

    private fun setupListeners() {
        // SeekBar listeners
        findViewById<SeekBar>(R.id.seekMaxSteps).setOnSeekBarChangeListener(
            SimpleSeekListener { value ->
                Preferences.maxSteps = value
                findViewById<TextView>(R.id.labelMaxSteps).text = "Max Steps: $value"
            }
        )

        findViewById<SeekBar>(R.id.seekTemperature).setOnSeekBarChangeListener(
            SimpleSeekListener { value ->
                val temp = value / 100f
                Preferences.temperature = temp
                findViewById<TextView>(R.id.labelTemperature).text = "Temperature: $temp"
            }
        )

        findViewById<SeekBar>(R.id.seekMaxTokens).setOnSeekBarChangeListener(
            SimpleSeekListener { value ->
                Preferences.maxTokens = value
                findViewById<TextView>(R.id.labelMaxTokens).text = "Max Tokens: $value"
            }
        )

        // Switch listeners
        findViewById<Switch>(R.id.switchCompression).setOnCheckedChangeListener { _, isChecked ->
            Preferences.useScreenCompression = isChecked
        }
        findViewById<Switch>(R.id.switchSkillMemory).setOnCheckedChangeListener { _, isChecked ->
            Preferences.skillMemoryEnabled = isChecked
        }
        findViewById<Switch>(R.id.switchConfirmDestructive).setOnCheckedChangeListener { _, isChecked ->
            Preferences.confirmDestructive = isChecked
        }
        findViewById<Switch>(R.id.switchOverlay).setOnCheckedChangeListener { _, isChecked ->
            Preferences.overlayEnabled = isChecked
        }
        findViewById<Switch>(R.id.switchVoice).setOnCheckedChangeListener { _, isChecked ->
            Preferences.voiceEnabled = isChecked
        }
        findViewById<Switch>(R.id.switchTelegram).setOnCheckedChangeListener { _, isChecked ->
            Preferences.telegramEnabled = isChecked
        }

        // Save button
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }

        // Fetch models button
        findViewById<Button>(R.id.btnFetchModels).setOnClickListener { fetchModels() }

        // Open accessibility
        findViewById<Button>(R.id.btnOpenA11y).setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun saveSettings() {
        Preferences.apiKey = findViewById<EditText>(R.id.inputApiKey).text.toString().trim()
        Preferences.baseUrl = findViewById<EditText>(R.id.inputBaseUrl).text.toString().trim()
        Preferences.model = findViewById<EditText>(R.id.inputModel).text.toString().trim()
        Preferences.telegramBotToken = findViewById<EditText>(R.id.inputTelegramToken).text.toString().trim()

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun fetchModels() {
        lifecycleScope.launch {
            try {
                val models = llmClient.fetchAvailableModels()
                if (models.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, "No models found", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Select Model")
                    .setItems(models.toTypedArray()) { _, which ->
                        findViewById<EditText>(R.id.inputModel).setText(models[which])
                    }
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private class SimpleSeekListener(private val onProgress: (Int) -> Unit) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onProgress(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
