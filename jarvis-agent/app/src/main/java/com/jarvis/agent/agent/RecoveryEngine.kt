package com.jarvis.agent.agent

import android.util.Log

/**
 * Recovery Engine — rule-based diagnosis of action failures.
 *
 * When an atomic action fails in the agent loop, this engine suggests
 * a recovery action to try before the next LLM call.
 *
 * Strategy:
 * - Loading/spinner detected → wait
 * - Keyboard visible → press_back to dismiss
 * - Click failed → scroll (if scrollable) or press_back
 * - Open app failed → press_home
 * - Default → press_back
 */
class RecoveryEngine {

    companion object {
        private const val TAG = "RecoveryEngine"
    }

    data class RecoveryAction(
        val action: String,
        val params: Map<String, String> = emptyMap(),
        val reason: String
    )

    /**
     * Diagnose the failure and suggest a recovery action.
     *
     * @param lastFailedAction The action that just failed
     * @param screenContent The current screen dump
     * @return RecoveryAction to execute before the next LLM call
     */
    fun diagnose(lastFailedAction: String, screenContent: String): RecoveryAction {
        val lowerScreen = screenContent.lowercase()

        // 1. Loading/spinner detection
        if (containsLoadingIndicator(lowerScreen)) {
            Log.d(TAG, "Recovery: loading detected → wait")
            return RecoveryAction("wait", emptyMap(), "Screen appears to be loading")
        }

        // 2. Keyboard visible — dismiss it
        if (isKeyboardVisible(lowerScreen)) {
            Log.d(TAG, "Recovery: keyboard visible → press_back")
            return RecoveryAction("press_back", emptyMap(), "Dismiss keyboard")
        }

        // 3. Click failure — try scrolling or going back
        if (lastFailedAction in listOf("click_text", "click_at")) {
            if (hasScrollableContent(lowerScreen)) {
                Log.d(TAG, "Recovery: click failed → scroll")
                return RecoveryAction("scroll", mapOf("direction" to "down"), "Element might be off-screen")
            }
            Log.d(TAG, "Recovery: click failed → press_back")
            return RecoveryAction("press_back", emptyMap(), "Navigate back and retry")
        }

        // 4. Open app failure
        if (lastFailedAction == "open_app") {
            Log.d(TAG, "Recovery: app open failed → press_home")
            return RecoveryAction("press_home", emptyMap(), "Reset to home screen")
        }

        // 5. Type text failure — try clicking the field first
        if (lastFailedAction == "type_text") {
            Log.d(TAG, "Recovery: type failed → press_back")
            return RecoveryAction("press_back", emptyMap(), "Dismiss and retry")
        }

        // 6. Default recovery
        Log.d(TAG, "Recovery: default → press_back")
        return RecoveryAction("press_back", emptyMap(), "Default recovery: go back")
    }

    private fun containsLoadingIndicator(screen: String): Boolean {
        val loadingTerms = listOf("loading", "please wait", "spinner", "progress", "refreshing", "fetching")
        return loadingTerms.any { it in screen }
    }

    private fun isKeyboardVisible(screen: String): Boolean {
        val keyboardTerms = listOf("keyboard", "ime", "com.google.android.inputmethod")
        return keyboardTerms.any { it in screen }
    }

    private fun hasScrollableContent(screen: String): Boolean {
        return "[scroll]" in screen || "scroll" in screen
    }
}
