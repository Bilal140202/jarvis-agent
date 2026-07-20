package com.jarvis.agent.agent

import android.util.Log
import com.jarvis.agent.llm.*
import com.jarvis.agent.service.AgentAccessibilityService
import com.jarvis.agent.service.AppLauncherService
import com.jarvis.agent.util.Constants
import com.jarvis.agent.util.Preferences
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Task Executor — the "brain" of the agent.
 *
 * Implements the core agent loop:
 * 1. Read current screen state via AccessibilityService
 * 2. Build prompt with screen dump + previous result
 * 3. Call LLM to decide next atomic action
 * 4. Execute the action
 * 5. Observe result and repeat until "done" or max steps reached
 *
 * Includes safety mechanisms:
 * - Consecutive failure tracking and abort
 * - Repeat action limits per action type
 * - Recovery engine for failed actions
 * - Skill memory for replaying learned tasks
 * - Cancellation support
 */
class TaskExecutor(
    private val context: android.content.Context,
    private val llmClient: LlmClient
) {
    companion object {
        private const val TAG = "TaskExecutor"
    }

    private val appLauncher = AppLauncherService(context)
    private val recoveryEngine = RecoveryEngine()
    private val skillMemory = SkillMemory(context)

    private var cancelled = false
    private val cancelMutex = Mutex()

    data class TaskResult(
        val success: Boolean,
        val details: String,
        val steps: List<ActionStep> = emptyList()
    )

    /**
     * Execute a multi-step UI automation task.
     *
     * @param userGoal The natural language task description
     * @return TaskResult with success status and details
     */
    suspend fun executeTask(userGoal: String): TaskResult = withContext(Dispatchers.Main) {
        Log.i(TAG, "Starting task: $userGoal")
        cancelled = false

        // ─── Pre-flight checks ────────────────────────────────
        val service = AgentAccessibilityService.instance
        if (service == null) {
            return@withContext TaskResult(false, "Accessibility service is not running. Please enable it in Settings → Accessibility.")
        }

        // ─── Skill memory check ───────────────────────────────
        if (Preferences.skillMemoryEnabled) {
            val existingSkill = skillMemory.findSkill(userGoal)
            if (existingSkill != null && existingSkill.isReliable) {
                Log.i(TAG, "Found reliable skill for '$userGoal', replaying...")
                val replayResult = replaySkill(existingSkill, service)
                if (replayResult.success) {
                    existingSkill.successCount++
                    existingSkill.lastUsed = System.currentTimeMillis()
                    skillMemory.updateSkill(existingSkill)
                    return@withContext replayResult
                } else {
                    Log.w(TAG, "Skill replay failed, falling back to AI")
                    existingSkill.failCount++
                    skillMemory.updateSkill(existingSkill)
                }
            }
        }

        // ─── Navigation shortcuts ─────────────────────────────
        applyNavigationShortcuts(userGoal, service)

        // If currently in Jarvis app, press Home first
        if (service.getCurrentPackage() == "com.jarvis.agent") {
            service.pressHome()
            delay(500)
        }

        // ─── Agent Loop ───────────────────────────────────────
        val maxSteps = Preferences.maxSteps
        val executedSteps = mutableListOf<ActionStep>()
        var consecutiveFailures = 0
        var previousResult = "No previous action"
        val actionCounts = mutableMapOf<String, Int>()

        for (step in 0 until maxSteps) {
            // Check cancellation
            cancelMutex.withLock { if (cancelled) return@withContext TaskResult(false, "Task cancelled by user", executedSteps) }

            // Adaptive delay based on last action type
            val delayMs = if (step == 0) 1000L else {
                val lastAction = executedSteps.lastOrNull()?.action
                when (lastAction) {
                    Constants.ACTION_OPEN_APP -> 3000L
                    Constants.ACTION_WAIT -> 1500L
                    Constants.ACTION_SCROLL, Constants.ACTION_SWIPE -> 1500L
                    else -> 1000L
                }
            }
            delay(delayMs)

            // 1. Read screen
            val screenContent = service.getScreenDescription(
                taskGoal = userGoal,
                compressed = Preferences.useScreenCompression
            )

            if (screenContent.lines().size <= 1) {
                // Screen might be empty/loading
                delay(1000)
                continue
            }

            // 2. Build failure warning if needed
            val failureWarning = if (consecutiveFailures >= Constants.FAILURE_WARNING_THRESHOLD) {
                "You have failed $consecutiveFailures times in a row. Try a COMPLETELY DIFFERENT approach. Consider scrolling, pressing back, or tapping different elements."
            } else ""

            // 3. Call LLM (with cancel race)
            val atomicAction = try {
                withTimeoutOrNull(Constants.LLM_TIMEOUT_S * 1000) {
                    llmClient.sendTaskMessage(
                        userGoal = userGoal,
                        screenContent = screenContent,
                        previousResult = previousResult,
                        step = step + 1,
                        maxSteps = maxSteps,
                        failureWarning = failureWarning
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "LLM call failed at step ${step + 1}", e)
                consecutiveFailures++
                previousResult = "Error: ${e.message}"
                continue
            }

            if (atomicAction == null) {
                consecutiveFailures++
                previousResult = "Failed to get action from AI"
                continue
            }

            Log.i(TAG, "Step ${step + 1}: ${atomicAction.action} ${atomicAction.params} — ${atomicAction.reasoning}")

            // 4. Check for completion
            if (atomicAction.action == Constants.ACTION_DONE || atomicAction.isComplete) {
                val result = TaskResult(true, atomicAction.reasoning.ifBlank { "Task completed" }, executedSteps)
                // Save to skill memory
                if (Preferences.skillMemoryEnabled && executedSteps.isNotEmpty()) {
                    skillMemory.saveSkill(userGoal, executedSteps)
                }
                Log.i(TAG, "Task completed: ${atomicAction.reasoning}")
                return@withContext result
            }

            // 5. Safety: check repeat limits
            val actionCount = actionCounts.getOrDefault(atomicAction.action, 0)
            val maxRepeats = when (atomicAction.action) {
                Constants.ACTION_PRESS_ENTER -> Constants.MAX_PRESS_ENTER_REPEATS
                Constants.ACTION_SCROLL -> Constants.MAX_SCROLL_REPEATS
                Constants.ACTION_SWIPE -> Constants.MAX_SWIPE_REPEATS
                else -> 1000
            }
            if (actionCount >= maxRepeats) {
                Log.w(TAG, "Action ${atomicAction.action} repeated $actionCount times, forcing different action")
                previousResult = "You've already used ${atomicAction.action} $actionCount times. You MUST try a different approach now."
                continue
            }

            // 6. Safety gate: check for destructive actions
            if (Preferences.confirmDestructive && isDestructiveAction(atomicAction)) {
                Log.w(TAG, "Blocking destructive action: ${atomicAction.action}")
                previousResult = "BLOCKED: This action looks potentially destructive (payment, delete, uninstall). " +
                    "Disable 'Confirm Destructive Actions' in Settings to allow. Skipping this step."
                continue
            }

            // 7. Execute the action
            val success = executeAtomicAction(atomicAction, service)

            if (success) {
                consecutiveFailures = 0
                actionCounts[atomicAction.action] = actionCount + 1
                executedSteps.add(ActionStep(atomicAction.action, atomicAction.params))
                previousResult = "Action succeeded: ${atomicAction.reasoning}"
            } else {
                consecutiveFailures++
                previousResult = "Action failed: ${atomicAction.reasoning}"

                // Apply recovery
                if (consecutiveFailures < Constants.MAX_CONSECUTIVE_FAILURES) {
                    val recovery = recoveryEngine.diagnose(atomicAction.action, screenContent)
                    executeRecoveryAction(recovery, service)
                    previousResult += " | Recovery: ${recovery.action}"
                }
            }

            // 7. Abort if too many failures
            if (consecutiveFailures >= Constants.MAX_CONSECUTIVE_FAILURES) {
                Log.w(TAG, "Aborting: $consecutiveFailures consecutive failures")
                return@withContext TaskResult(false, "Task failed: too many consecutive errors. $consecutiveFailures failures in a row.", executedSteps)
            }
        }

        // Max steps reached
        Log.w(TAG, "Task reached max steps: $maxSteps")
        TaskResult(false, "Task reached maximum steps ($maxSteps) without completion.", executedSteps)
    }

    /**
     * Execute a single atomic action via the AccessibilityService.
     */
    private fun executeAtomicAction(action: AtomicAction, service: AgentAccessibilityService): Boolean {
        return when (action.action) {
            Constants.ACTION_CLICK_TEXT -> {
                val text = action.param("text") ?: return false
                service.clickByText(text)
            }
            Constants.ACTION_CLICK_AT -> {
                val x = action.intParam("x") ?: return false
                val y = action.intParam("y") ?: return false
                service.clickAt(x, y)
            }
            Constants.ACTION_TYPE_TEXT -> {
                val text = action.param("text") ?: return false
                val hint = action.param("field_hint")
                service.typeText(text, hint)
            }
            Constants.ACTION_PRESS_ENTER -> {
                service.pressEnter()
            }
            Constants.ACTION_SCROLL -> {
                val direction = action.param("direction") ?: "down"
                service.scroll(direction)
            }
            Constants.ACTION_SWIPE -> {
                val metrics = service.resources.displayMetrics
                val w = metrics.widthPixels.toFloat()
                val h = metrics.heightPixels.toFloat()
                val sx = (action.intParam("start_x")?.toFloat() ?: 50f) / 100f * w
                val sy = (action.intParam("start_y")?.toFloat() ?: 70f) / 100f * h
                val ex = (action.intParam("end_x")?.toFloat() ?: 50f) / 100f * w
                val ey = (action.intParam("end_y")?.toFloat() ?: 30f) / 100f * h
                service.swipe(sx, sy, ex, ey)
            }
            Constants.ACTION_PRESS_BACK -> {
                service.pressBack()
            }
            Constants.ACTION_PRESS_HOME -> {
                service.pressHome()
            }
            Constants.ACTION_OPEN_APP -> {
                val appName = action.param("app_name") ?: return false
                appLauncher.openApp(appName)
            }
            Constants.ACTION_WAIT -> {
                kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(1000) }
                true
            }
            else -> {
                Log.w(TAG, "Unknown atomic action: ${action.action}")
                false
            }
        }
    }

    /**
     * Execute a recovery action.
     */
    private fun executeRecoveryAction(recovery: RecoveryEngine.RecoveryAction, service: AgentAccessibilityService) {
        when (recovery.action) {
            "wait" -> kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(2000) }
            "press_back" -> service.pressBack()
            "scroll" -> service.scroll(recovery.params["direction"] ?: "down")
            "press_home" -> service.pressHome()
        }
    }

    /**
     * Apply navigation shortcuts for common tasks (skip LLM for speed).
     */
    private fun applyNavigationShortcuts(goal: String, service: AgentAccessibilityService) {
        val lower = goal.lowercase()
        when {
            "dark mode" in lower || "dark theme" in lower -> {
                appLauncher.openApp("Settings")
                kotlinx.coroutines.runBlocking { delay(2000) }
                service.clickByText("Display")
            }
            "wifi" in lower && ("turn on" in lower || "enable" in lower || "open" in lower) -> {
                appLauncher.openApp("Settings")
                kotlinx.coroutines.runBlocking { delay(2000) }
                service.clickByText("Network")
            }
            "bluetooth" in lower && ("turn on" in lower || "enable" in lower || "open" in lower) -> {
                appLauncher.openApp("Settings")
                kotlinx.coroutines.runBlocking { delay(2000) }
                service.clickByText("Connected")
            }
        }
    }

    /**
     * Replay a learned skill without LLM calls.
     */
    private suspend fun replaySkill(skill: SavedSkill, service: AgentAccessibilityService): TaskResult {
        Log.i(TAG, "Replaying skill: ${skill.task} (${skill.steps.size} steps)")
        for ((index, step) in skill.steps.withIndex()) {
            cancelMutex.withLock { if (cancelled) return TaskResult(false, "Cancelled during replay") }
            delay(1000)

            val action = AtomicAction(
                action = step.action,
                params = step.params,
                reasoning = "Replaying step ${index + 1}",
                isComplete = index == skill.steps.lastIndex
            )
            val success = executeAtomicAction(action, service)
            if (!success) {
                Log.w(TAG, "Skill replay failed at step ${index + 1}")
                return TaskResult(false, "Skill replay failed at step ${index + 1}")
            }
        }
        return TaskResult(true, "Task completed (replayed from memory)", skill.steps)
    }

    /**
     * Cancel the currently running task.
     */
    suspend fun cancel() {
        cancelMutex.withLock { cancelled = true }
        Log.i(TAG, "Task cancellation requested")
    }

    /**
     * Detect potentially destructive actions based on text content.
     * Blocks: payments, deletions, uninstalls, data clearing, etc.
     */
    private fun isDestructiveAction(action: AtomicAction): Boolean {
        val text = (action.param("text") ?: "").lowercase()
        val destructivePatterns = listOf(
            "pay", "purchase", "buy", "checkout", "order",
            "delete", "remove", "uninstall", "clear data", "erase",
            "format", "factory reset", "reset", "wipe",
            "confirm payment", "place order", "subscribe"
        )
        return destructivePatterns.any { text.contains(it) }
    }

    /**
     * Shizuku fallback for actions that fail via AccessibilityService.
     */
    private val shizukuService = com.jarvis.agent.service.ShizukuService()

    private suspend fun tryShizukuFallback(action: String, params: Map<String, Any>): Boolean {
        if (!Preferences.shizukuEnabled || !shizukuService.isAvailable()) return false
        return when (action) {
            Constants.ACTION_PRESS_ENTER -> shizukuService.pressKey(66) // KEYCODE_ENTER
            Constants.ACTION_SCROLL -> {
                val metrics = context.resources.displayMetrics
                val w = metrics.widthPixels
                val h = metrics.heightPixels
                val dir = params["direction"]?.toString() ?: "down"
                if (dir == "down") {
                    shizukuService.inputSwipe(w / 2, (h * 0.7).toInt(), w / 2, (h * 0.3).toInt())
                } else {
                    shizukuService.inputSwipe(w / 2, (h * 0.3).toInt(), w / 2, (h * 0.7).toInt())
                }
            }
            Constants.ACTION_SWIPE -> {
                val metrics = context.resources.displayMetrics
                val w = metrics.widthPixels
                val h = metrics.heightPixels
                val sx = ((params["start_x"]?.toString()?.toIntOrNull() ?: 50) * w / 100)
                val sy = ((params["start_y"]?.toString()?.toIntOrNull() ?: 70) * h / 100)
                val ex = ((params["end_x"]?.toString()?.toIntOrNull() ?: 50) * w / 100)
                val ey = ((params["end_y"]?.toString()?.toIntOrNull() ?: 30) * h / 100)
                shizukuService.inputSwipe(sx, sy, ex, ey)
            }
            else -> false
        }
    }
}
