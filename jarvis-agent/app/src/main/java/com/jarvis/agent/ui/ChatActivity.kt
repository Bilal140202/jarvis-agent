package com.jarvis.agent.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.agent.R
import com.jarvis.agent.agent.ActionHandler
import com.jarvis.agent.llm.ChatMessage
import com.jarvis.agent.llm.LlmClient
import com.jarvis.agent.llm.RouteAction
import com.jarvis.agent.service.AgentAccessibilityService
import com.jarvis.agent.service.AgentLoopService
import com.jarvis.agent.service.OverlayService
import com.jarvis.agent.service.VoiceService
import com.jarvis.agent.util.Constants
import com.jarvis.agent.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main Chat Activity — the primary user interface.
 *
 * Features:
 * - Chat-style message list with user and assistant bubbles
 * - Text input + send button
 * - Voice input via microphone button
 * - Agent mode toggle (Chat ↔ Agent)
 * - Accessibility service status indicator
 * - Settings access
 * - Overlay toggle
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ChatActivity"
        private const val REQUEST_RECORD_AUDIO = 1001
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var voiceButton: ImageButton
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var modeToggle: ToggleButton
    private lateinit var progressBar: ProgressBar

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: ChatAdapter
    private val llmClient = LlmClient()
    private val voiceService by lazy { VoiceService(this) }
    private var isAgentMode = true
    private var isProcessing = false

    // Chat history for conversational mode
    private val chatHistory = mutableListOf<ChatMessage>()

    private val taskReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Constants.ACTION_TASK_COMPLETE,
                Constants.ACTION_TASK_UPDATE -> {
                    val msg = intent.getStringExtra(Constants.EXTRA_TASK_MESSAGE) ?: ""
                    val status = intent.getStringExtra(Constants.EXTRA_TASK_STATUS) ?: ""
                    val emoji = if (status == "success") "✓" else "✗"
                    addAssistantMessage("$emoji $msg")
                    isProcessing = false
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check onboarding
        if (!Preferences.onboardingCompleted) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        // Check configuration
        if (!Preferences.isConfigured()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        // Initialize views
        recyclerView = findViewById(R.id.recyclerView)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        voiceButton = findViewById(R.id.voiceButton)
        statusIndicator = findViewById(R.id.statusIndicator)
        statusText = findViewById(R.id.statusText)
        modeToggle = findViewById(R.id.modeToggle)
        progressBar = findViewById(R.id.progressBar)

        // Setup RecyclerView
        chatAdapter = ChatAdapter(messages)
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }

        // Mode toggle
        modeToggle.setOnCheckedChangeListener { _, isChecked ->
            isAgentMode = !isChecked // Toggle: Agent ON = default, Chat ON = checked
            val mode = if (isAgentMode) "Agent" else "Chat"
            Toast.makeText(this, "Mode: $mode", Toast.LENGTH_SHORT).show()
        }

        // Send button
        sendButton.setOnClickListener { sendMessage() }

        // Voice button
        voiceButton.setOnClickListener {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                startVoiceInput()
            } else {
                Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            }
        }

        // Initialize TTS
        voiceService.initTts()

        // Welcome message
        addAssistantMessage("Hello! I'm Jarvis, your Android assistant. How can I help you today?")

        // Register broadcast receiver
        registerReceiver(taskReceiver, IntentFilter().apply {
            addAction(Constants.ACTION_TASK_COMPLETE)
            addAction(Constants.ACTION_TASK_UPDATE)
        })

        // Start overlay if enabled
        if (Preferences.overlayEnabled) {
            OverlayService.show(this)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(taskReceiver) } catch (_: Exception) {}
        voiceService.destroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_overlay -> {
                if (OverlayService.isActive) {
                    OverlayService.hide(this)
                    item.title = "Show Overlay"
                } else {
                    OverlayService.show(this)
                    item.title = "Hide Overlay"
                }
                true
            }
            R.id.action_accessibility -> {
                // Open accessibility settings
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startVoiceInput()
            }
        }
    }

    // ─── Message Handling ─────────────────────────────────────

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isBlank() || isProcessing) return

        messageInput.text.clear()
        addUserMessage(text)

        if (isAgentMode) {
            processAgentMessage(text)
        } else {
            processChatMessage(text)
        }
    }

    private fun processAgentMessage(text: String) {
        isProcessing = true
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val actionHandler = ActionHandler(this@ChatActivity)
                val routeAction = llmClient.sendRouteMessage(text)

                if (routeAction == null) {
                    addAssistantMessage("I couldn't understand that. Could you rephrase?")
                    return@launch
                }

                // Show the LLM's response message
                if (routeAction.response.isNotBlank()) {
                    addAssistantMessage(routeAction.response)
                }

                // Execute the routed action
                val result = actionHandler.execute(routeAction)

                val emoji = if (result.success) "✓" else "✗"
                val resultMsg = if (result.details.isNullOrBlank()) {
                    if (result.success) "Done!" else "Something went wrong."
                } else {
                    result.details
                }
                addAssistantMessage("$emoji $resultMsg")

                // Speak result if voice enabled
                if (Preferences.voiceEnabled && result.success) {
                    voiceService.speak(resultMsg)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Agent processing failed", e)
                addAssistantMessage("Error: ${e.message}")
            } finally {
                isProcessing = false
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun processChatMessage(text: String) {
        isProcessing = true
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val response = llmClient.sendChatMessage(text, chatHistory)
                if (response != null) {
                    chatHistory.add(ChatMessage("user", text))
                    chatHistory.add(ChatMessage("assistant", response))
                    addAssistantMessage(response)
                    if (Preferences.voiceEnabled) {
                        voiceService.speak(response)
                    }
                } else {
                    addAssistantMessage("I couldn't generate a response. Please check your API settings.")
                }
            } catch (e: Exception) {
                addAssistantMessage("Error: ${e.message}")
            } finally {
                isProcessing = false
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun startVoiceInput() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }

        lifecycleScope.launch {
            try {
                val text = voiceService.listen()
                if (text.isNotBlank()) {
                    messageInput.setText(text)
                    sendMessage()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Voice input failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ─── UI Helpers ───────────────────────────────────────────

    private fun addUserMessage(text: String) {
        messages.add(ChatMessage("user", text))
        chatAdapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)
    }

    private fun addAssistantMessage(text: String) {
        messages.add(ChatMessage("assistant", text))
        chatAdapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)
    }

    private fun updateServiceStatus() {
        val running = AgentAccessibilityService.isRunning()
        statusIndicator.setBackgroundResource(
            if (running) android.R.drawable.presence_online else android.R.drawable.presence_offline
        )
        statusText.text = if (running) "Connected" else "Not Connected"
    }
}
