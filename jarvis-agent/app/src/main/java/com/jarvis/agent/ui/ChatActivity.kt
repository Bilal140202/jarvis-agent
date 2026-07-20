package com.jarvis.agent.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.text.format.DateUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.jarvis.agent.R
import com.jarvis.agent.agent.ActionHandler
import com.jarvis.agent.llm.ChatMessage
import com.jarvis.agent.llm.LlmClient
import com.jarvis.agent.service.AgentAccessibilityService
import com.jarvis.agent.service.AgentLoopService
import com.jarvis.agent.service.JarvisBackgroundService
import com.jarvis.agent.service.OverlayService
import com.jarvis.agent.service.VoiceService
import com.jarvis.agent.util.Constants
import com.jarvis.agent.util.Preferences
import com.jarvis.agent.util.TaskHistoryManager
import com.jarvis.agent.util.TaskHistoryManager.TaskRecord
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

    // Chat UI
    private lateinit var messageList: ListView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var voiceButton: ImageButton
    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var modeToggle: ToggleButton
    private lateinit var progressBar: ProgressBar
    private lateinit var menuButton: ImageButton
    private lateinit var taskHistoryButton: ImageButton

    // Side Menu UI
    private lateinit var menuOverlay: FrameLayout
    private lateinit var menuDrawer: LinearLayout
    private lateinit var maxStepsSlider: SeekBar
    private lateinit var maxStepsValue: TextView
    private lateinit var menuSettings: LinearLayout
    private lateinit var menuAccessibility: LinearLayout
    private lateinit var menuOverlayToggle: LinearLayout
    private lateinit var overlayStatusText: TextView
    private lateinit var menuAbout: LinearLayout

    // Task History UI
    private lateinit var taskHistoryOverlay: FrameLayout
    private lateinit var taskHistoryDrawer: LinearLayout
    private lateinit var closeDrawerButton: ImageButton
    private lateinit var starredTaskList: ListView
    private lateinit var todayTaskList: ListView
    private lateinit var starredSectionHeader: TextView
    private lateinit var todaySectionHeader: TextView
    private lateinit var emptyTaskMessage: TextView
    private lateinit var drawerStarredCount: TextView

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var chatAdapter: MessageListAdapter
    private val llmClient = LlmClient()
    private var voiceService: VoiceService? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var isAgentMode = true
    private var isProcessing = false
    private val chatHistory = mutableListOf<ChatMessage>()
    private var isTaskDrawerOpen = false
    private var isMenuOpen = false

    // Track last task for saving to history
    private var lastTaskText: String? = null

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

                        // Update background service notification
                        JarvisBackgroundService.updateStatus(
                            this@ChatActivity,
                            if (status == "success") "Done: ${msg.take(40)}" else "Error: ${msg.take(40)}"
                        )

                        // Save task to history
                        val taskText = lastTaskText
                        if (taskText != null) {
                            TaskHistoryManager.saveTask(taskText, msg)
                            lastTaskText = null
                        }
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

            // ── Chat UI ──
            messageList = findViewById(R.id.messageList)
            messageInput = findViewById(R.id.messageInput)
            sendButton = findViewById(R.id.sendButton)
            voiceButton = findViewById(R.id.voiceButton)
            statusIndicator = findViewById(R.id.statusIndicator)
            statusText = findViewById(R.id.statusText)
            modeToggle = findViewById(R.id.modeToggle)
            progressBar = findViewById(R.id.progressBar)
            menuButton = findViewById(R.id.menuButton)
            taskHistoryButton = findViewById(R.id.taskHistoryButton)

            // ── Side Menu UI ──
            menuOverlay = findViewById(R.id.menuOverlay)
            menuDrawer = findViewById(R.id.menuDrawer)
            maxStepsSlider = findViewById(R.id.maxStepsSlider)
            maxStepsValue = findViewById(R.id.maxStepsValue)
            menuSettings = findViewById(R.id.menuSettings)
            menuAccessibility = findViewById(R.id.menuAccessibility)
            menuOverlayToggle = findViewById(R.id.menuOverlayToggle)
            overlayStatusText = findViewById(R.id.overlayStatusText)
            menuAbout = findViewById(R.id.menuAbout)

            // ── Task History UI ──
            taskHistoryOverlay = findViewById(R.id.taskHistoryOverlay)
            taskHistoryDrawer = findViewById(R.id.taskHistoryDrawer)
            closeDrawerButton = findViewById(R.id.closeDrawerButton)
            starredTaskList = findViewById(R.id.starredTaskList)
            todayTaskList = findViewById(R.id.todayTaskList)
            starredSectionHeader = findViewById(R.id.starredSectionHeader)
            todaySectionHeader = findViewById(R.id.todaySectionHeader)
            emptyTaskMessage = findViewById(R.id.emptyTaskMessage)
            drawerStarredCount = findViewById(R.id.drawerStarredCount)

            chatAdapter = MessageListAdapter(this, messages)
            messageList.adapter = chatAdapter

            // Mode toggle
            modeToggle.setOnCheckedChangeListener { _, isChecked ->
                isAgentMode = !isChecked
                Toast.makeText(this, "Mode: ${if (isAgentMode) "Agent" else "Chat"}", Toast.LENGTH_SHORT).show()
            }

            // Send button
            sendButton.setOnClickListener { sendMessage() }

            // Enter key sends message
            messageInput.setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER && event.isCtrlPressed.not())
                ) {
                    // Only send on Enter (not Shift+Enter for multiline)
                    if (event == null || !event.isShiftPressed) {
                        sendMessage()
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }

            // Also handle raw key events for hardware keyboards
            messageInput.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN && !event.isShiftPressed) {
                    sendMessage()
                    true
                } else {
                    false
                }
            }

            // Voice button
            voiceButton.setOnClickListener {
                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    startVoiceInput()
                }
            }

            // ── Menu Drawer ──
            menuButton.setOnClickListener { openMenuDrawer() }
            menuOverlay.setOnClickListener { closeMenuDrawer() }
            menuDrawer.setOnClickListener { /* consume clicks */ }

            // Max steps slider
            maxStepsSlider.progress = Preferences.maxSteps
            maxStepsValue.text = Preferences.maxSteps.toString()
            maxStepsSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val steps = if (progress < 5) 5 else progress
                    maxStepsValue.text = steps.toString()
                    if (fromUser) Preferences.maxSteps = steps
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            // Menu item clicks
            menuSettings.setOnClickListener {
                closeMenuDrawer()
                startActivity(Intent(this, SettingsActivity::class.java))
            }

            menuAccessibility.setOnClickListener {
                closeMenuDrawer()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }

            menuOverlayToggle.setOnClickListener {
                closeMenuDrawer()
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
            }

            menuAbout.setOnClickListener {
                closeMenuDrawer()
                Toast.makeText(this, "Jarvis Agent v1.0.0\nYour Android Assistant", Toast.LENGTH_LONG).show()
            }

            // ── Task History Drawer ──
            taskHistoryButton.setOnClickListener { openTaskDrawer() }
            closeDrawerButton.setOnClickListener { closeTaskDrawer() }
            taskHistoryOverlay.setOnClickListener { closeTaskDrawer() }
            taskHistoryDrawer.setOnClickListener { /* consume clicks */ }

            // Initialize voice safely
            try {
                voiceService = VoiceService(this)
                voiceService?.initTts()
            } catch (e: Exception) {
                Log.e(TAG, "TTS init failed", e)
            }

            addAssistantMessage("Hello! I'm Jarvis, your Android assistant. How can I help you today?")

            // Cleanup expired tasks on start
            TaskHistoryManager.cleanupExpired()

            // Start background service for permanent notification
            startBackgroundService()

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
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in onCreate", e)
            Toast.makeText(this, "Error starting Jarvis: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            updateServiceStatus()
            TaskHistoryManager.cleanupExpired()
            overlayStatusText.text = if (OverlayService.isActive) "ON" else "OFF"
            // Ensure background service is running
            if (!JarvisBackgroundService.isRunning) {
                startBackgroundService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(taskReceiver) } catch (_: Exception) {}
        try { voiceService?.destroy() } catch (_: Exception) {}
        // Don't stop background service on destroy — it should persist
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // No longer using action bar menu — we have sidebar instead
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return super.onOptionsItemSelected(item)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startVoiceInput()
        }
    }

    override fun onBackPressed() {
        when {
            isMenuOpen -> closeMenuDrawer()
            isTaskDrawerOpen -> closeTaskDrawer()
            else -> {
                // Move to background instead of finishing
                moveTaskToBack(true)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Background Service
    // ═══════════════════════════════════════════════════════════════

    private fun startBackgroundService() {
        try {
            JarvisBackgroundService.start(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start background service", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Menu Drawer
    // ═══════════════════════════════════════════════════════════════

    private fun openMenuDrawer() {
        isMenuOpen = true
        menuOverlay.visibility = View.VISIBLE
        // Refresh overlay status
        overlayStatusText.text = if (OverlayService.isActive) "ON" else "OFF"
        maxStepsSlider.progress = Preferences.maxSteps
        maxStepsValue.text = Preferences.maxSteps.toString()
    }

    private fun closeMenuDrawer() {
        isMenuOpen = false
        menuOverlay.visibility = View.GONE
    }

    // ═══════════════════════════════════════════════════════════════
    // Task History Drawer
    // ═══════════════════════════════════════════════════════════════

    private fun openTaskDrawer() {
        isTaskDrawerOpen = true
        taskHistoryOverlay.visibility = View.VISIBLE
        refreshTaskDrawer()
    }

    private fun closeTaskDrawer() {
        isTaskDrawerOpen = false
        taskHistoryOverlay.visibility = View.GONE
    }

    private fun refreshTaskDrawer() {
        try {
            val allTasks = TaskHistoryManager.getAllTasks()
            val starred = allTasks.filter { it.isStarred }
            val today = TaskHistoryManager.getTodayTasks().filter { !it.isStarred }

            // Starred section
            if (starred.isNotEmpty()) {
                starredSectionHeader.visibility = View.VISIBLE
                starredTaskList.visibility = View.VISIBLE
                starredTaskList.adapter = TaskListAdapter(this, starred) { task ->
                    TaskHistoryManager.toggleStar(task.id)
                    refreshTaskDrawer()
                }
                drawerStarredCount.text = "Starred: ${starred.size}"
            } else {
                starredSectionHeader.visibility = View.GONE
                starredTaskList.visibility = View.GONE
                drawerStarredCount.text = "Starred: 0"
            }

            // Today section
            if (today.isNotEmpty()) {
                todaySectionHeader.visibility = View.VISIBLE
                todayTaskList.visibility = View.VISIBLE
                emptyTaskMessage.visibility = View.GONE
                todayTaskList.adapter = TaskListAdapter(this, today) { task ->
                    TaskHistoryManager.toggleStar(task.id)
                    refreshTaskDrawer()
                }
            } else if (starred.isEmpty()) {
                todaySectionHeader.visibility = View.GONE
                todayTaskList.visibility = View.GONE
                emptyTaskMessage.visibility = View.VISIBLE
            } else {
                todaySectionHeader.visibility = View.GONE
                todayTaskList.visibility = View.GONE
                emptyTaskMessage.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing task drawer", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Message Handling
    // ═══════════════════════════════════════════════════════════════

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
        lastTaskText = text

        // Update background notification
        JarvisBackgroundService.updateStatus(this, "Analyzing: ${text.take(30)}")

        scope.launch {
            try {
                val routeAction = llmClient.sendRouteMessage(text)
                if (routeAction == null) {
                    addAssistantMessage("I couldn't understand that. Could you rephrase?")
                    lastTaskText = null
                    JarvisBackgroundService.updateStatus(this@ChatActivity, "Ready")
                    return@launch
                }

                JarvisBackgroundService.updateStatus(this@ChatActivity, "Executing: ${routeAction.action}")

                if (routeAction.response.isNotBlank()) addAssistantMessage(routeAction.response)
                val actionHandler = ActionHandler(this@ChatActivity)
                val result = actionHandler.execute(routeAction)
                val resultMsg = result.details ?: if (result.success) "Done!" else "Something went wrong."
                addAssistantMessage(resultMsg)
                if (Preferences.voiceEnabled && result.success) voiceService?.speak(resultMsg)

                // Save to task history
                TaskHistoryManager.saveTask(text, resultMsg)
                lastTaskText = null
                JarvisBackgroundService.updateStatus(this@ChatActivity, "Done: ${resultMsg.take(30)}")
            } catch (e: Exception) {
                addAssistantMessage("Error: ${e.message}")
                lastTaskText = null
                JarvisBackgroundService.updateStatus(this@ChatActivity, "Error: ${e.message?.take(30)}")
            } finally {
                isProcessing = false
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun processChatMessage(text: String) {
        isProcessing = true
        progressBar.visibility = View.VISIBLE
        JarvisBackgroundService.updateStatus(this, "Chatting...")

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
                JarvisBackgroundService.updateStatus(this@ChatActivity, "Ready")
            } catch (e: Exception) {
                addAssistantMessage("Error: ${e.message}")
                JarvisBackgroundService.updateStatus(this@ChatActivity, "Ready")
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

    // ═══════════════════════════════════════════════════════════════
    // Adapters
    // ═══════════════════════════════════════════════════════════════

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

    /**
     * ListView adapter for task history items.
     */
    class TaskListAdapter(
        private val context: android.app.Activity,
        private val tasks: List<TaskRecord>,
        private val onStarToggle: (TaskRecord) -> Unit
    ) : BaseAdapter() {

        override fun getCount() = tasks.size
        override fun getItem(position: Int) = tasks[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val task = tasks[position]

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8, 10, 8, 10)
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xFF1A1A2E.toInt())
            }

            val starBtn = TextView(context).apply {
                text = if (task.isStarred) "★" else "☆"
                textSize = 20f
                setTextColor(if (task.isStarred) 0xFFFFD700.toInt() else 0xFF606060.toInt())
                setPadding(8, 4, 8, 4)
                setOnClickListener { onStarToggle(task) }
            }

            val textContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 0, 8, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val taskText = TextView(context).apply {
                text = task.text
                textSize = 14f
                setTextColor(0xFFE0E0E0.toInt())
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            val resultText = TextView(context).apply {
                text = task.result.takeIf { it.isNotBlank() } ?: ""
                textSize = 12f
                setTextColor(0xFF808080.toInt())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            textContainer.addView(taskText)
            if (resultText.text.isNotBlank()) {
                textContainer.addView(resultText)
            }

            val timeText = TextView(context).apply {
                text = DateUtils.getRelativeTimeSpanString(
                    task.timestamp,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                )
                textSize = 10f
                setTextColor(0xFF505050.toInt())
                setPadding(4, 0, 4, 0)
            }

            val deleteBtn = TextView(context).apply {
                text = "✕"
                textSize = 16f
                setTextColor(0xFFFF6B6B.toInt())
                setPadding(8, 4, 8, 4)
                setOnClickListener {
                    TaskHistoryManager.deleteTask(task.id)
                    (context as? ChatActivity)?.refreshTaskDrawer()
                }
            }

            container.addView(starBtn)
            container.addView(textContainer)
            container.addView(timeText)
            container.addView(deleteBtn)

            return container
        }
    }
}
