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
import com.jarvis.agent.service.AgentLoopService
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

    // Task History UI
    private lateinit var taskHistoryBar: LinearLayout
    private lateinit var taskCountBadge: TextView
    private lateinit var starredCountBadge: TextView
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
    private var isDrawerOpen = false

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

                        // Save task to history
                        val taskText = lastTaskText
                        if (taskText != null) {
                            TaskHistoryManager.saveTask(taskText, msg)
                            lastTaskText = null
                            refreshTaskBadges()
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

            // Chat UI bindings
            messageList = findViewById(R.id.messageList)
            messageInput = findViewById(R.id.messageInput)
            sendButton = findViewById(R.id.sendButton)
            voiceButton = findViewById(R.id.voiceButton)
            statusIndicator = findViewById(R.id.statusIndicator)
            statusText = findViewById(R.id.statusText)
            modeToggle = findViewById(R.id.modeToggle)
            progressBar = findViewById(R.id.progressBar)

            // Task History UI bindings
            taskHistoryBar = findViewById(R.id.taskHistoryBar)
            taskCountBadge = findViewById(R.id.taskCountBadge)
            starredCountBadge = findViewById(R.id.starredCountBadge)
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

            sendButton.setOnClickListener { sendMessage() }

            voiceButton.setOnClickListener {
                if (SpeechRecognizer.isRecognitionAvailable(this)) {
                    startVoiceInput()
                }
            }

            // Task History drawer logic
            taskHistoryBar.setOnClickListener { openTaskDrawer() }
            closeDrawerButton.setOnClickListener { closeTaskDrawer() }
            taskHistoryOverlay.setOnClickListener { closeTaskDrawer() }
            taskHistoryDrawer.setOnClickListener { /* consume clicks to prevent closing */ }

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
            refreshTaskBadges()

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
            refreshTaskBadges()
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

    override fun onBackPressed() {
        if (isDrawerOpen) {
            closeTaskDrawer()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Task History Drawer
    // ═══════════════════════════════════════════════════════════════

    private fun openTaskDrawer() {
        isDrawerOpen = true
        taskHistoryOverlay.visibility = View.VISIBLE
        refreshTaskDrawer()
    }

    private fun closeTaskDrawer() {
        isDrawerOpen = false
        taskHistoryOverlay.visibility = View.GONE
    }

    private fun refreshTaskBadges() {
        try {
            val allTasks = TaskHistoryManager.getAllTasks()
            val starred = allTasks.count { it.isStarred }
            val unstarred = allTasks.size - starred
            taskCountBadge.text = unstarred.toString()
            starredCountBadge.text = "★ $starred"
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing task badges", e)
        }
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
                    refreshTaskBadges()
                }
                drawerStarredCount.text = "★ Starred: ${starred.size}"
            } else {
                starredSectionHeader.visibility = View.GONE
                starredTaskList.visibility = View.GONE
                drawerStarredCount.text = "★ Starred: 0"
            }

            // Today section
            if (today.isNotEmpty()) {
                todaySectionHeader.visibility = View.VISIBLE
                todayTaskList.visibility = View.VISIBLE
                emptyTaskMessage.visibility = View.GONE
                todayTaskList.adapter = TaskListAdapter(this, today) { task ->
                    TaskHistoryManager.toggleStar(task.id)
                    refreshTaskDrawer()
                    refreshTaskBadges()
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
        lastTaskText = text  // Track for task history saving

        scope.launch {
            try {
                val routeAction = llmClient.sendRouteMessage(text)
                if (routeAction == null) {
                    addAssistantMessage("I couldn't understand that. Could you rephrase?")
                    lastTaskText = null
                    return@launch
                }
                if (routeAction.response.isNotBlank()) addAssistantMessage(routeAction.response)
                val actionHandler = ActionHandler(this@ChatActivity)
                val result = actionHandler.execute(routeAction)
                val resultMsg = result.details ?: if (result.success) "Done!" else "Something went wrong."
                addAssistantMessage(resultMsg)
                if (Preferences.voiceEnabled && result.success) voiceService?.speak(resultMsg)

                // Save to task history
                TaskHistoryManager.saveTask(text, resultMsg)
                lastTaskText = null
                refreshTaskBadges()
            } catch (e: Exception) {
                addAssistantMessage("Error: ${e.message}")
                lastTaskText = null
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
     * Each item shows: star button | task text | result snippet | delete button
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

            // Star button
            val starBtn = TextView(context).apply {
                text = if (task.isStarred) "★" else "☆"
                textSize = 20f
                setTextColor(if (task.isStarred) 0xFFFFD700.toInt() else 0xFF606060.toInt())
                setPadding(8, 4, 8, 4)
                setOnClickListener { onStarToggle(task) }
            }

            // Task text + result
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

            // Time label
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

            // Delete button
            val deleteBtn = TextView(context).apply {
                text = "✕"
                textSize = 16f
                setTextColor(0xFFFF6B6B.toInt())
                setPadding(8, 4, 8, 4)
                setOnClickListener {
                    TaskHistoryManager.deleteTask(task.id)
                    (context as? ChatActivity)?.let { activity ->
                        activity.refreshTaskDrawer()
                        activity.refreshTaskBadges()
                    }
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
