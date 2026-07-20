package com.jarvis.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.jarvis.agent.util.Constants

/**
 * Core Accessibility Service — the "eyes and hands" of Jarvis.
 *
 * Provides:
 * - Screen tree dump (recursive AccessibilityNodeInfo traversal)
 * - Gesture dispatch (tap, swipe, scroll via dispatchGesture)
 * - Text input (ACTION_SET_TEXT on editable nodes)
 * - Key press simulation (back, home, enter)
 * - Screenshot capture (Android 9+)
 *
 * Uses a static singleton pattern so the rest of the app can access it
 * from any context (Activity, Service, etc.).
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentA11yService"

        /** Static singleton — accessible from anywhere in the app. */
        var instance: AgentAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    // Optional listener for click/scroll events forwarded to UI
    var eventListener: ((Map<String, Any>) -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    0 // FLAG_DEFAULT not available in all API levels
            notificationTimeout = 100
        }
        serviceInfo = info

        Log.i(TAG, "AgentAccessibilityService connected and configured")
        Toast.makeText(this, "Jarvis Agent Active", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "AgentAccessibilityService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // Forward relevant events to listener (for UI feedback)
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                eventListener?.invoke(mapOf(
                    "type" to event.eventType,
                    "packageName" to (event.packageName?.toString() ?: ""),
                    "text" to (event.text?.joinToString(" ") ?: "")
                ))
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AgentAccessibilityService interrupted")
    }

    // ═══════════════════════════════════════════════════════════
    // SCREEN DUMP — Recursive UI tree traversal
    // ═══════════════════════════════════════════════════════════

    /**
     * Data class representing a single UI element on screen.
     */
    data class UiElement(
        val index: Int,
        val text: String?,
        val contentDescription: String?,
        val className: String?,
        val bounds: Rect,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val isFocusable: Boolean,
        val isChecked: Boolean?,
        val depth: Int,
        val viewIdResource: String?
    )

    /**
     * Dumps the current screen's interactive UI elements as a flat list.
     * Filters out invisible, zero-size, and self-referencing nodes.
     */
    fun dumpScreen(): List<UiElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<UiElement>()
        var index = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            // Skip invisible or zero-size nodes
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) {
                return
            }
            // Skip self (Jarvis app) nodes
            if (node.packageName?.toString() == "com.jarvis.agent") {
                return
            }
            // Only include interactive or informative elements
            if (node.isClickable || node.isEditable || node.isScrollable ||
                node.isFocusable || node.isCheckable || !node.text.isNullOrBlank() ||
                !node.contentDescription.isNullOrBlank()
            ) {
                elements.add(UiElement(
                    index = index++,
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = simplifyClassName(node.className?.toString()),
                    bounds = bounds,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isFocusable = node.isFocusable,
                    isChecked = if (node.isCheckable) node.isChecked else null,
                    depth = depth,
                    viewIdResource = node.viewIdResourceName
                ))
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }

        walk(root, 0)
        return elements
    }

    /**
     * Returns a human-readable screen description suitable for LLM consumption.
     * Compressed mode uses shorter class names and filters noise.
     */
    fun getScreenDescription(taskGoal: String? = null, compressed: Boolean = true): String {
        val elements = dumpScreen()
        val pkg = getCurrentPackage() ?: "unknown"

        val sb = StringBuilder()
        sb.appendLine("APP: $pkg")

        for (el in elements) {
            val mark = if (taskGoal != null && matchesGoal(el, taskGoal)) "*" else ""
            val interactions = mutableListOf<String>()
            if (el.isClickable) interactions.add("tap")
            if (el.isEditable) interactions.add("edit")
            if (el.isScrollable) interactions.add("scroll")

            val label = el.text ?: el.contentDescription ?: ""
            val truncatedLabel = if (compressed && label.length > 50) label.take(50) + "…" else label

            sb.appendLine(
                "[${el.index}]$mark ${el.className} \"$truncatedLabel\" " +
                "${if (interactions.isNotEmpty()) interactions.joinToString(",", "[", "]") else ""} " +
                "center:(${el.bounds.centerX()},${el.bounds.centerY()})" +
                (if (el.isChecked == true) " [checked]" else "")
            )
        }
        return sb.toString()
    }

    private fun matchesGoal(el: UiElement, goal: String): Boolean {
        val lowerGoal = goal.lowercase()
        val text = (el.text ?: "").lowercase()
        val desc = (el.contentDescription ?: "").lowercase()
        return text.split(" ").any { it.length > 2 && lowerGoal.contains(it) } ||
               desc.split(" ").any { it.length > 2 && lowerGoal.contains(it) }
    }

    private fun simplifyClassName(className: String?): String {
        if (className == null) return "view"
        return when {
            className.contains("TextView") -> "text"
            className.contains("Button") -> "btn"
            className.contains("EditText") -> "input"
            className.contains("ImageView") -> "img"
            className.contains("Switch") || className.contains("Toggle") -> "toggle"
            className.contains("CheckBox") -> "checkbox"
            className.contains("RadioButton") -> "radio"
            className.contains("RecyclerView") || className.contains("ListView") -> "list"
            className.contains("ScrollView") -> "scroll"
            className.contains("SeekBar") || className.contains("Slider") -> "slider"
            className.contains("Toolbar") || className.contains("ActionBar") -> "toolbar"
            className.contains("TabLayout") || className.contains("Tab") -> "tab"
            className.contains("Drawer") -> "drawer"
            className.contains("WebView") -> "webview"
            else -> "view"
        }
    }

    // ═══════════════════════════════════════════════════════════
    // GESTURE DISPATCH — Tap, swipe, scroll
    // ═══════════════════════════════════════════════════════════

    /**
     * Tap at specific screen coordinates.
     */
    fun tap(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 100L)
            val builder = android.accessibilityservice.GestureDescription.Builder()
            builder.addStroke(stroke)
            dispatchGesture(builder.build(), null, null)
            Log.d(TAG, "Tapped at ($x, $y)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Tap failed at ($x, $y)", e)
            false
        }
    }

    /**
     * Click a node by visible text. Tries exact match first, then contains.
     * Falls back to coordinate-based tap if ACTION_CLICK fails.
     */
    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, text) ?: return false

        // Try ACTION_CLICK first (more reliable for accessibility)
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            Log.d(TAG, "Clicked by text: '$text' via ACTION_CLICK")
            return true
        }

        // Fallback: tap the center coordinates
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val tapped = tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        Log.d(TAG, "Clicked by text: '$text' via coordinate tap → $tapped")
        return tapped
    }

    /**
     * Click at specific coordinates (x, y as percentages 0-100 of screen).
     */
    fun clickAt(x: Int, y: Int): Boolean {
        val displayMetrics = resources.displayMetrics
        val absX = (x.toFloat() / 100f) * displayMetrics.widthPixels
        val absY = (y.toFloat() / 100f) * displayMetrics.heightPixels
        return tap(absX, absY)
    }

    /**
     * Swipe gesture from (startX, startY) to (endX, endY).
     */
    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 600L): Boolean {
        return try {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, duration)
            val builder = android.accessibilityservice.GestureDescription.Builder()
            builder.addStroke(stroke)
            dispatchGesture(builder.build(), null, null)
            Log.d(TAG, "Swiped from ($startX,$startY) to ($endX,$endY)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Swipe failed", e)
            false
        }
    }

    /**
     * Perform a directional scroll.
     */
    fun scroll(direction: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(root) ?: return false

        val result = when (direction.lowercase()) {
            "down", "forward" -> scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "up", "backward" -> scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            else -> false
        }

        if (!result) {
            // Fallback: gesture-based scroll
            val metrics = resources.displayMetrics
            val centerX = metrics.widthPixels / 2f
            val startY = metrics.heightPixels * 0.7f
            val endY = if (direction.lowercase() in listOf("down", "forward"))
                metrics.heightPixels * 0.3f else metrics.heightPixels * 0.7f
            val actualStartY = if (direction.lowercase() in listOf("down", "forward")) startY else endY
            val actualEndY = if (direction.lowercase() in listOf("down", "forward")) endY else startY
            return swipe(centerX, actualStartY, centerX, actualEndY)
        }

        Log.d(TAG, "Scrolled $direction via ACTION_SCROLL")
        return result
    }

    // ═══════════════════════════════════════════════════════════
    // TEXT INPUT
    // ═══════════════════════════════════════════════════════════

    /**
     * Type text into a focused or specified editable field.
     */
    fun typeText(text: String, fieldHint: String? = null): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = if (fieldHint != null) {
            findEditableByHint(root, fieldHint)
        } else {
            findFocusedEditable(root) ?: findAnyEditable(root)
        } ?: return false

        // Focus the node first
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // Set text via ACTION_SET_TEXT
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val result = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.d(TAG, "Typed '$text' into field → $result")
        return result
    }

    /**
     * Press Enter / Submit on the currently focused field.
     * Three-tier strategy: IME action → find action button → tap IME area.
     */
    fun pressEnter(): Boolean {
        val root = rootInActiveWindow ?: return false

        // Strategy 1: IME Enter action (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val result = focused.performAction(0x10000000) // ACTION_IME_ENTER (API 30+)
                if (result) {
                    Log.d(TAG, "Pressed Enter via ACTION_IME_ENTER")
                    return true
                }
            }
        }

        // Strategy 2: Find action button (Search, Go, Done, Send, Enter)
        val actionLabels = listOf("search", "go", "done", "send", "enter", "submit")
        val actionNode = findNodeByContentDesc(root, actionLabels)
        if (actionNode != null) {
            val result = actionNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) {
                Log.d(TAG, "Pressed Enter via action button")
                return true
            }
        }

        // Strategy 3: Perform ACTION_CLICK on focused editable (some apps handle this)
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) {
            val result = focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (result) {
                Log.d(TAG, "Pressed Enter via focused editable click")
                return true
            }
        }

        Log.w(TAG, "Press Enter failed — no strategy worked")
        return false
    }

    // ═══════════════════════════════════════════════════════════
    // GLOBAL ACTIONS — Back, Home, Recents
    // ═══════════════════════════════════════════════════════════

    fun pressBack(): Boolean {
        val result = performGlobalAction(GLOBAL_ACTION_BACK)
        Log.d(TAG, "Press Back → $result")
        return result
    }

    fun pressHome(): Boolean {
        val result = performGlobalAction(GLOBAL_ACTION_HOME)
        Log.d(TAG, "Press Home → $result")
        return result
    }

    fun pressRecents(): Boolean {
        val result = performGlobalAction(GLOBAL_ACTION_RECENTS)
        Log.d(TAG, "Press Recents → $result")
        return result
    }

    fun openNotifications(): Boolean {
        val result = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        Log.d(TAG, "Open Notifications → $result")
        return result
    }

    fun openQuickSettings(): Boolean {
        val result = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        Log.d(TAG, "Open Quick Settings → $result")
        return result
    }

    // ═══════════════════════════════════════════════════════════
    // UTILITY — Node finding helpers
    // ═══════════════════════════════════════════════════════════

    fun getCurrentPackage(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        // Exact match first
        val exact = root.findAccessibilityNodeInfosByText(text)
            .filter { !it.text.isNullOrBlank() && it.text.toString().equals(text, ignoreCase = true) }
            .sortedByDescending { !it.isEditable } // Prefer non-editable (buttons over fields)
        if (exact.isNotEmpty()) return exact.first()

        // Contains match
        val contains = root.findAccessibilityNodeInfosByText(text)
            .filter { !it.text.isNullOrBlank() && it.text.toString().contains(text, ignoreCase = true) }
            .sortedByDescending { !it.isEditable }
        return contains.firstOrNull()
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findScrollableNode(child)?.let { return it }
            }
        }
        return null
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return if (focused != null && focused.isEditable) focused else null
    }

    private fun findAnyEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable) return root
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                findAnyEditable(child)?.let { return it }
            }
        }
        return null
    }

    private fun findEditableByHint(root: AccessibilityNodeInfo, hint: String): AccessibilityNodeInfo? {
        if (root.isEditable) {
            val desc = root.contentDescription?.toString()?.lowercase() ?: ""
            val hintStr = root.hintText?.toString()?.lowercase() ?: ""
            if (desc.contains(hint, ignoreCase = true) || hintStr.contains(hint, ignoreCase = true)) {
                return root
            }
        }
        for (i in 0 until root.childCount) {
            root.getChild(i)?.let { child ->
                findEditableByHint(child, hint)?.let { return it }
            }
        }
        return null
    }

    private fun findNodeByContentDesc(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val desc = child.contentDescription?.toString()?.lowercase() ?: ""
            val text = child.text?.toString()?.lowercase() ?: ""
            if (labels.any { desc.contains(it) || text.contains(it) }) {
                if (child.isClickable) return child
            }
            findNodeByContentDesc(child, labels)?.let { return it }
        }
        return null
    }
}
