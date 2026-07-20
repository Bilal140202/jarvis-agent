package com.jarvis.agent.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
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
 * Uses native Android ListView (no AndroidX dependency).
 */
class ChatActivity : android.app.Activity() {

    companion object {
        private const val TAG = "ChatActivity"
        private const val REQUEST_RECORD_AUDIO = 1001
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
    private val voiceService by lazy { VoiceService(this) }
    private val scope = CoroutineScope(Dispatchers.Main)
    private var isAgentMode = true
    private var isProcessing = false
    private val chatHistory = mutableListOf<ChatMessage>()

    private val taskReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
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
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        voiceService.initTts()
        addAssistantMessage("Hello! I'm Jarvis, your Android assistant. How can I help you today?")

        registerReceiver(taskReceiver, IntentFilter().apply {
            addAction(Constants.ACTION_TASK_COMPLETE)
            addAction(Constants.ACTION_TASK_UPDATE)
        })

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
                if (OverlayService.isActive) OverlayService.hide(this) else OverlayService.show(this)
                true
            }
            R.id.action_accessibility -> {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
                if (Preferences.voiceEnabled && result.success) voiceService.speak(resultMsg)
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
                    if (Preferences.voiceEnabled) voiceService.speak(response)
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
                val text = voiceService.listen()
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
