package com.jarvis.agent.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.jarvis.agent.R
import com.jarvis.agent.agent.ActionHandler
import com.jarvis.agent.llm.ChatMessage
import com.jarvis.agent.llm.LlmClient
import com.jarvis.agent.service.AgentAccessibilityService
import com.jarvis.agent.service.OverlayService
import com.jarvis.agent.service.VoiceService
import com.jarvis.agent.util.Constants
import com.jarvis.agent.util.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Main Chat Activity — the primary user interface.
 */
class ChatActivity : android.app.Activity() {

    companion object {
        private const val TAG = "ChatActivity"
        private const val REQUEST_RECORD_AUDIO = 1001
        private const val REQUEST_OVERLAY = 1002
        private const val REQUEST_NOTIFICATIONS = 1003
    }

    private lateinit var messageList: ListView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var voiceButton: ImageButton
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var modeToggle: ToggleButton
    private lateinit var progressBar: ProgressBar

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: MessageListAdapter
    private val llmClient = LlmClient()
    private var voiceService: VoiceService? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var isAgentMode = true
    private var isProcessing = false
    private val chatHistory = mutableListOf<ChatMessage>()

    private val taskReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    Constants.ACTION_TASK_COMPLETE,
                    Constants.ACTION_TASK_UPDATE -> {
                        val msg = intent.getStringExtra(Constants.EXTRA_TASK_MESSAGE) ?: ""
                        val status = intent.getStringExtra(Constants.EXTRA_TASK_STATUS) ?: ""
                        addAssistantMessage("${if (status == "success") "OK" else "X"} $msg")
                        isProcessing = false
                        progressBar.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in taskReceiver", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // Check if onboarding needed
            if (!Preferences.onboardingCompleted || !Preferences.isConfigured()) {
                startActivity(Intent(this, OnboardingActivity::class.java))
                finish()
                return
            }

            setContentView(R.layout.activity_main)

            messageList = findViewById(R.id.messageList)
            messageInput = findViewById(R.id.messageInput)
            sendButton = findViewById(R.id.sendButton)
            voiceButton = findViewById(R.id.voiceButton)
            statusIndicator = findViewById(R.id.statusIndicator)
            statusText = findViewById(R.id.statusText)
            modeToggle = findViewById(R.id.modeToggle)
            progressBar = findViewById(R.id.progressBar)

            chatAdapter = MessageListAdapter(this, messages)
            messageList.adapter = chatAdapter

            modeToggle.setOnCheckedChangeListener { _, isChecked ->
                isAgentMode = !isChecked
                Toast.makeText(this, "Mode: ${if (isAgentMode) "Agent" else "Chat"}", Toast.LENGTH_SHORT).show()
            }

            sendButton.setOnClickListener { sendMessage() }

            voiceButton.setOnClickListener {
                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    startVoiceInput()
                }
            }

            // Initialize voice safely
            try {
                voiceService = VoiceService(this)
                voiceService?.initTts()
            } catch (e: Exception) {
                Log.e(TAG, "TTS init failed", e)
            }

            addAssistantMessage("Hello! I'm Jarvis, your Android assistant. How can I help you today?")

            // Register broadcast receiver safely
            try {
                val filter = IntentFilter().apply {
                    addAction(Constants.ACTION_TASK_COMPLETE)
                    addAction(Constants.ACTION_TASK_UPDATE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(taskReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(taskReceiver, filter)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register receiver", e)
            }

            // Request POST_NOTIFICATIONS permission for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_NOTIFICATIONS
                    )
                }
            }

            // Don't auto-start overlay — let user enable it manually
            // Overlay requires SYSTEM_ALERT_WINDOW permission which must be granted in Settings
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in onCreate", e)
            Toast.makeText(this, "Error starting Jarvis: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            updateServiceStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(taskReceiver) } catch (_: Exception) {}
        try { voiceService?.destroy() } catch (_: Exception) {}
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
                try {
                    if (!Settings.canDrawOverlays(this)) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        intent.data = android.net.Uri.parse("package:$packageName")
                        startActivity(intent)
                        Toast.makeText(this, "Grant overlay permission, then try again", Toast.LENGTH_LONG).show()
                    } else if (OverlayService.isActive) {
                        OverlayService.hide(this)
                    } else {
                        OverlayService.show(this)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Overlay error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_accessibility -> {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startVoiceInput()
        }
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isBlank() || isProcessing) return
        messageInput.text.clear()
        addUserMessage(text)
        if (isAgentMode) processAgentMessage(text) else processChatMessage(text)
    }

    private fun processAgentMessage(text: String) {
        isProcessing = true
        progressBar.visibility = View.VISIBLE
        scope.launch {
            try {
                val routeAction = llmClient.sendRouteMessage(text)
                if (routeAction == null) {
                    addAssistantMessage("I couldn't understand that. Could you rephrase?")
                    return@launch
                }
                if (routeAction.response.isNotBlank()) addAssistantMessage(routeAction.response)
                val actionHandler = ActionHandler(this@ChatActivity)
                val result = actionHandler.execute(routeAction)
                val resultMsg = result.details ?: if (result.success) "Done!" else "Something went wrong."
                addAssistantMessage(resultMsg)
                if (Preferences.voiceEnabled && result.success) voiceService?.speak(resultMsg)
            } catch (e: Exception) {
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
        scope.launch {
            try {
                val response = llmClient.sendChatMessage(text, chatHistory)
                if (response != null) {
                    chatHistory.add(ChatMessage("user", text))
                    chatHistory.add(ChatMessage("assistant", response))
                    addAssistantMessage(response)
                    if (Preferences.voiceEnabled) voiceService?.speak(response)
                } else {
                    addAssistantMessage("Could not generate a response. Check your API settings.")
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
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        scope.launch {
            try {
                val text = voiceService?.listen() ?: ""
                if (text.isNotBlank()) { messageInput.setText(text); sendMessage() }
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Voice failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addUserMessage(text: String) {
        messages.add(ChatMessage("user", text))
        chatAdapter.notifyDataSetChanged()
        scrollToEnd()
    }

    private fun addAssistantMessage(text: String) {
        messages.add(ChatMessage("assistant", text))
        chatAdapter.notifyDataSetChanged()
        scrollToEnd()
    }

    private fun scrollToEnd() {
        messageList.post { messageList.setSelection(messages.size - 1) }
    }

    private fun updateServiceStatus() {
        val running = AgentAccessibilityService.isRunning()
        statusIndicator.setBackgroundResource(
            if (running) android.R.drawable.presence_online else android.R.drawable.presence_offline
        )
        statusText.text = if (running) "Connected" else "Not Connected"
    }

    /**
     * Simple ListView adapter for chat messages.
     */
    class MessageListAdapter(
        private val context: android.app.Activity,
        private val messages: List<ChatMessage>
    ) : BaseAdapter() {

        override fun getCount() = messages.size
        override fun getItem(position: Int) = messages[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val msg = messages[position]
            val isUser = msg.role == "user"

            val tv = TextView(context).apply {
                text = msg.content
                textSize = 15f
                setPadding(24, 16, 24, 16)
                maxWidth = (context.resources.displayMetrics.widthPixels * 0.75).toInt()
            }

            if (isUser) {
                tv.setTextColor(0xFFFFFFFF.toInt())
                tv.setBackgroundColor(0xFF6C63FF.toInt())
            } else {
                tv.setTextColor(0xFFE0E0E0.toInt())
                tv.setBackgroundColor(0xFF1A1A2E.toInt())
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 4, 8, 4)
                gravity = if (isUser) Gravity.END else Gravity.START
                addView(tv)
            }

            return container
        }
    }
}
