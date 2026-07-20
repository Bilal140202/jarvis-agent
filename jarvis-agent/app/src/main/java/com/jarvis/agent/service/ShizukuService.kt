package com.jarvis.agent.service

import android.util.Log

/**
 * Shizuku Service — elevated ADB commands via Shizuku.
 *
 * Shizuku allows the agent to run ADB-level commands without root,
 * providing capabilities that AccessibilityService alone cannot:
 * - Force-stop apps
 * - Clear app data
 * - Input keyevents (more reliable than AccessibilityService)
 * - Toggle WiFi/Bluetooth
 * - Grant/revoke permissions
 *
 * Requirements:
 * - Shizuku app installed and running on device
 * - User has authorized Shizuku
 * - User has granted permission to Jarvis via Shizuku
 */
class ShizukuService {

    companion object {
        private const val TAG = "ShizukuService"
    }

    /**
     * Check if Shizuku is available and authorized.
     */
    fun isAvailable(): Boolean {
        return try {
            // Check if Shizuku binder is accessible
            val clazz = Class.forName("moe.shizuku.api.ShizukuService")
            val method = clazz.getMethod("pingBinder")
            method.invoke(null) as? Boolean ?: false
        } catch (e: Exception) {
            Log.d(TAG, "Shizuku not available: ${e.message}")
            false
        }
    }

    /**
     * Run an ADB shell command via Shizuku.
     * Falls back to Runtime.exec if Shizuku is unavailable.
     */
    fun runCommand(command: String): String? {
        return try {
            // Try Shizuku first
            val clazz = Class.forName("moe.shizuku.api.ShizukuService")
            val method = clazz.getMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
            process?.inputStream?.bufferedReader()?.readText()?.trim()
        } catch (e: Exception) {
            Log.d(TAG, "Shizuku exec failed, trying Runtime: ${e.message}")
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                process.inputStream.bufferedReader().readText().trim()
            } catch (e2: Exception) {
                Log.e(TAG, "Command failed: $command", e2)
                null
            }
        }
    }

    // ─── Convenience methods ─────────────────────────────────────

    fun pressKey(keyCode: Int): Boolean {
        val result = runCommand("input keyevent $keyCode")
        return result != null
    }

    fun inputSwipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int = 600): Boolean {
        val result = runCommand("input swipe $startX $startY $endX $endY $durationMs")
        return result != null
    }

    fun inputText(text: String): Boolean {
        val result = runCommand("input text \"$text\"")
        return result != null
    }

    fun toggleWifi(enable: Boolean): Boolean {
        val action = if (enable) "enable" else "disable"
        val result = runCommand("svc wifi $action")
        return result != null
    }

    fun toggleBluetooth(enable: Boolean): Boolean {
        // Bluetooth needs root or specific permissions
        val action = if (enable) "enable" else "disable"
        val result = runCommand("svc bluetooth $action") ?: runCommand("settings put global bluetooth_on ${if (enable) 1 else 0}")
        return result != null
    }

    fun forceStopApp(packageName: String): Boolean {
        val result = runCommand("am force-stop $packageName")
        return result != null
    }

    fun clearAppData(packageName: String): Boolean {
        val result = runCommand("pm clear $packageName")
        return result?.contains("Success") == true
    }
}
