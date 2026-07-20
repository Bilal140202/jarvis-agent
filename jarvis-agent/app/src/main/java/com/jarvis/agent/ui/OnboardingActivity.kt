package com.jarvis.agent.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jarvis.agent.R
import com.jarvis.agent.service.AgentAccessibilityService
import com.jarvis.agent.util.Preferences

/**
 * Onboarding Activity — first-time setup wizard.
 *
 * Steps:
 * 0. Welcome + permission requests (Accessibility, Mic, Contacts, Phone, SMS)
 * 1. AI Provider setup (API key, base URL, model)
 * 2. Ready screen + open accessibility settings shortcut
 */
class OnboardingActivity : AppCompatActivity() {

    private var currentStep = 0
    private lateinit var stepDots: List<View>
    private lateinit var contentArea: FrameLayout

    // Step 1 views
    private lateinit var providerSpinner: Spinner
    private lateinit var apiKeyInput: EditText
    private lateinit var baseUrlInput: EditText
    private lateinit var modelInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        contentArea = findViewById(R.id.contentArea)
        stepDots = listOf(
            findViewById(R.id.dot0),
            findViewById(R.id.dot1),
            findViewById(R.id.dot2)
        )

        showStep(0)

        findViewById<Button>(R.id.nextButton).setOnClickListener { nextStep() }
        findViewById<Button>(R.id.skipButton).setOnClickListener { finishOnboarding() }
    }

    private fun showStep(step: Int) {
        currentStep = step
        stepDots.forEachIndexed { index, view ->
            view.alpha = if (index == step) 1.0f else 0.3f
        }
        contentArea.removeAllViews()
        when (step) {
            0 -> showPermissionsStep()
            1 -> showProviderStep()
            2 -> showReadyStep()
        }
    }

    private fun nextStep() {
        if (currentStep == 1) {
            // Validate provider settings
            val apiKey = apiKeyInput.text.toString().trim()
            val baseUrl = baseUrlInput.text.toString().trim()
            val model = modelInput.text.toString().trim()

            if (apiKey.isBlank()) {
                apiKeyInput.error = "API key is required"
                return
            }
            if (baseUrl.isBlank()) {
                baseUrlInput.error = "Base URL is required"
                return
            }
            if (model.isBlank()) {
                modelInput.error = "Model name is required"
                return
            }

            Preferences.apiKey = apiKey
            Preferences.baseUrl = baseUrl
            Preferences.model = model
        }

        if (currentStep < 2) {
            showStep(currentStep + 1)
        } else {
            finishOnboarding()
        }
    }

    private fun showPermissionsStep() {
        val view = layoutInflater.inflate(R.layout.step_permissions, contentArea, false)
        contentArea.addView(view)

        // Check current permission states
        updatePermissionStatuses(view)

        // Request permissions button
        view.findViewById<Button>(R.id.btnRequestPerms).setOnClickListener {
            requestAllPermissions()
        }

        // Open accessibility settings
        view.findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun showProviderStep() {
        val view = layoutInflater.inflate(R.layout.step_provider, contentArea, false)
        contentArea.addView(view)

        providerSpinner = view.findViewById(R.id.providerSpinner)
        apiKeyInput = view.findViewById(R.id.apiKeyInput)
        baseUrlInput = view.findViewById(R.id.baseUrlInput)
        modelInput = view.findViewById(R.id.modelInput)

        // Provider presets
        val providers = arrayOf("Custom", "OpenAI", "DeepSeek", "OpenRouter", "NVIDIA NIM")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)
        providerSpinner.adapter = adapter

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                when (position) {
                    1 -> { // OpenAI
                        baseUrlInput.setText("https://api.openai.com/v1")
                        modelInput.setText("gpt-4o")
                    }
                    2 -> { // DeepSeek
                        baseUrlInput.setText("https://api.deepseek.com/v1")
                        modelInput.setText("deepseek-chat")
                    }
                    3 -> { // OpenRouter
                        baseUrlInput.setText("https://openrouter.ai/api/v1")
                        modelInput.setText("openai/gpt-4o")
                    }
                    4 -> { // NVIDIA NIM
                        baseUrlInput.setText("https://integrate.api.nvidia.com/v1")
                        modelInput.setText("meta/llama-3.1-8b-instruct")
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun showReadyStep() {
        val view = layoutInflater.inflate(R.layout.step_ready, contentArea, false)
        contentArea.addView(view)

        val a11yRunning = AgentAccessibilityService.isRunning()
        view.findViewById<ImageView>(R.id.readyIcon).setImageResource(
            if (a11yRunning) android.R.drawable.ic_dialog_info else android.R.drawable.ic_dialog_alert
        )
        view.findViewById<TextView>(R.id.readyStatus).text = if (a11yRunning) {
            "Jarvis is ready! The accessibility service is active."
        } else {
            "Almost ready! Please enable the accessibility service in Settings."
        }

        view.findViewById<Button>(R.id.btnOpenAccessibility2).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.POST_NOTIFICATIONS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 0)
        }
    }

    private fun updatePermissionStatuses(view: View) {
        val statusMic = view.findViewById<TextView>(R.id.statusMic)
        val statusContacts = view.findViewById<TextView>(R.id.statusContacts)
        val statusPhone = view.findViewById<TextView>(R.id.statusPhone)

        statusMic?.text = if (hasPermission(Manifest.permission.RECORD_AUDIO)) "✓" else "✗"
        statusContacts?.text = if (hasPermission(Manifest.permission.READ_CONTACTS)) "✓" else "✗"
        statusPhone?.text = if (hasPermission(Manifest.permission.CALL_PHONE)) "✓" else "✗"
    }

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun finishOnboarding() {
        Preferences.onboardingCompleted = true
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }
}
