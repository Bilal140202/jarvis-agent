package com.jarvis.agent.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jarvis.agent.ui.ChatActivity
import com.jarvis.agent.util.Constants
import kotlinx.coroutines.*

/**
 * Overlay Service — floating chat bubble that can be shown over any app.
 *
 * Two states:
 * - Collapsed: Small circular floating bubble (draggable)
 * - Expanded: Chat panel with input, messages, and send button
 *
 * Communicates with the main ChatActivity via broadcasts.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        var isActive = false
            private set

        fun show(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.startService(intent)
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var chatPanelView: View? = null
    private var isExpanded = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        isActive = true
        createNotificationChannel()
        startForeground(Constants.NOTIFICATION_OVERLAY, buildNotification())
        showBubble()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        removeViews()
        isActive = false
    }

    // ─── Bubble (collapsed) ───────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        removeViews()

        val bubbleSize = dpToPx(56)
        val params = WindowManager.LayoutParams(
            bubbleSize,
            bubbleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        val bubble = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setBackgroundResource(android.R.drawable.btn_default)
            setOnClickListener { showChatPanel() }
        }

        // Drag handling
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (dx * dx + dy * dy > 100) isDragging = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) bubble.performClick()
                    true
                }
                else -> false
            }
        }

        bubbleView = bubble
        windowManager?.addView(bubble, params)
    }

    // ─── Chat Panel (expanded) ────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showChatPanel() {
        removeViews()
        isExpanded = true

        val width = dpToPx(300)
        val height = dpToPx(400)
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // Build chat panel layout programmatically
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1A1A2E.toInt())
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Jarvis"
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val collapseBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundResource(0)
            setOnClickListener { showBubble() }
        }
        header.addView(title)
        header.addView(collapseBtn)
        panel.addView(header)

        // Message area
        val messageArea = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val messageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        messageArea.addView(messageContainer)
        panel.addView(messageArea)

        // Input area
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val inputField = EditText(this).apply {
            hint = "Ask Jarvis..."
            setTextColor(0xFFE0E0E0.toInt())
            setHintTextColor(0xFF808080.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sendBtn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setBackgroundResource(0)
            setOnClickListener {
                val msg = inputField.text.toString()
                if (msg.isNotBlank()) {
                    inputField.text.clear()
                    addMessage(messageContainer, "You: $msg")
                    processOverlayMessage(msg, messageContainer)
                }
            }
        }
        inputRow.addView(inputField)
        inputRow.addView(sendBtn)
        panel.addView(inputRow)

        chatPanelView = panel
        windowManager?.addView(panel, params)
    }

    private fun addMessage(container: LinearLayout, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 14f
            setPadding(0, dpToPx(4), 0, dpToPx(4))
        }
        container.addView(tv)
    }

    private fun processOverlayMessage(message: String, container: LinearLayout) {
        scope.launch {
            addMessage(container, "Jarvis: Processing...")
            // Delegate to the agent loop service
            AgentLoopService.start(this@OverlayService, message)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────

    private fun removeViews() {
        bubbleView?.let { windowManager?.removeView(it) }
        chatPanelView?.let { windowManager?.removeView(it) }
        bubbleView = null
        chatPanelView = null
        isExpanded = false
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, Constants.CHANNEL_OVERLAY)
            .setContentTitle("Jarvis Overlay")
            .setContentText("Floating assistant active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.CHANNEL_OVERLAY,
            "Overlay",
            NotificationManager.IMPORTANCE_MIN
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
