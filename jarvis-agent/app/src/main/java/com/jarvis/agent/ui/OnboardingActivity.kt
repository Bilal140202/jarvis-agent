package com.jarvis.agent.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import com.jarvis.agent.R
import com.jarvis.agent.service.AgentAccessibilityService
import com.jarvis.agent.util.Preferences

class OnboardingActivity : Activity() {

    private var currentStep = 0
    private lateinit var stepDots: List<View>
    private lateinit var contentArea: FrameLayout
    private lateinit var providerSpinner: Spinner
    private lateinit var apiKeyInput: EditText
    private lateinit var baseUrlInput: EditText
    private lateinit var modelInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        contentArea = findViewById(R.id.contentArea)
        stepDots = listOf(findViewById(R.id.dot0), findViewById(R.id.dot1), findViewById(R.id.dot2))
        showStep(0)
        findViewById<Button>(R.id.nextButton).setOnClickListener { nextStep() }
        findViewById<Button>(R.id.skipButton).setOnClickListener { finishOnboarding() }
    }

    private fun showStep(step: Int) {
        currentStep = step
        stepDots.forEachIndexed { index, view -> view.alpha = if (index == step) 1.0f else 0.3f }
        contentArea.removeAllViews()
        when (step) { 0 -> showPermissionsStep(); 1 -> showProviderStep(); 2 -> showReadyStep() }
    }

    private fun nextStep() {
        if (currentStep == 1) {
            val apiKey = apiKeyInput.text.toString().trim()
            val baseUrl = baseUrlInput.text.toString().trim()
            val model = modelInput.text.toString().trim()
            if (apiKey.isBlank()) { apiKeyInput.error = "API key required"; return }
            if (baseUrl.isBlank()) { baseUrlInput.error = "Base URL required"; return }
            if (model.isBlank()) { modelInput.error = "Model required"; return }
            Preferences.apiKey = apiKey; Preferences.baseUrl = baseUrl; Preferences.model = model
            when (providerSpinner.selectedItemPosition) {
                1 -> { Preferences.openaiApiKey = apiKey; Preferences.openaiModel = model; Preferences.providerPreset = "openai" }
                2 -> { Preferences.deepseekApiKey = apiKey; Preferences.deepseekModel = model; Preferences.providerPreset = "deepseek" }
                3 -> { Preferences.openrouterApiKey = apiKey; Preferences.openrouterModel = model; Preferences.providerPreset = "openrouter" }
                4 -> { Preferences.nimApiKey = apiKey; Preferences.nimModel = model; Preferences.providerPreset = "nvidia_nim" }
            }
        }
        if (currentStep < 2) showStep(currentStep + 1) else finishOnboarding()
    }

    private fun showPermissionsStep() {
        val view = layoutInflater.inflate(R.layout.step_permissions, contentArea, false)
        contentArea.addView(view)
        updatePermissionStatuses(view)
        view.findViewById<Button>(R.id.btnRequestPerms).setOnClickListener { requestAllPermissions() }
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
        val providers = arrayOf("Custom", "OpenAI", "DeepSeek", "OpenRouter", "NVIDIA NIM")
        providerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                when (position) {
                    1 -> { baseUrlInput.setText("https://api.openai.com/v1"); modelInput.setText("gpt-4o") }
                    2 -> { baseUrlInput.setText("https://api.deepseek.com/v1"); modelInput.setText("deepseek-chat") }
                    3 -> { baseUrlInput.setText("https://openrouter.ai/api/v1"); modelInput.setText("openai/gpt-4o") }
                    4 -> { baseUrlInput.setText("https://integrate.api.nvidia.com/v1"); modelInput.setText("meta/llama-3.1-8b-instruct") }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun showReadyStep() {
        val view = layoutInflater.inflate(R.layout.step_ready, contentArea, false)
        contentArea.addView(view)
        val a11yRunning = AgentAccessibilityService.isRunning()
        view.findViewById<TextView>(R.id.readyStatus).text = if (a11yRunning) "Jarvis is ready! Accessibility active." else "Enable the accessibility service in Settings."
        view.findViewById<Button>(R.id.btnOpenAccessibility2).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)
        val needed = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 0)
    }

    private fun updatePermissionStatuses(view: View) {
        view.findViewById<TextView>(R.id.statusMic)?.text = if (hasPermission(Manifest.permission.RECORD_AUDIO)) "OK" else "X"
        view.findViewById<TextView>(R.id.statusContacts)?.text = if (hasPermission(Manifest.permission.READ_CONTACTS)) "OK" else "X"
        view.findViewById<TextView>(R.id.statusPhone)?.text = if (hasPermission(Manifest.permission.CALL_PHONE)) "OK" else "X"
    }

    private fun hasPermission(perm: String) = checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun finishOnboarding() {
        Preferences.onboardingCompleted = true
        startActivity(Intent(this, ChatActivity::class.java))
        finish()
    }
}
