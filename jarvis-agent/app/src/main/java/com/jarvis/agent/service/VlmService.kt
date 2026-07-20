package com.jarvis.agent.service

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.jarvis.agent.llm.LlmClient
import com.jarvis.agent.util.Preferences
import java.io.ByteArrayOutputStream

/**
 * VLM (Vision Language Model) Service — uses screenshots + VLM for visual understanding.
 *
 * When enabled, the agent loop can optionally use screenshots analyzed by a VLM
 * (like GPT-4o vision) instead of relying solely on the accessibility tree dump.
 * This is especially useful for:
 * - Games and canvas-based apps with no accessibility nodes
 * - Image-heavy UIs (Instagram, Pinterest)
 * - Custom/proprietary UI frameworks
 */
class VlmService {

    companion object {
        private const val TAG = "VlmService"
        private const val MAX_IMAGE_DIMENSION = 1024
        private const val JPEG_QUALITY = 60
    }

    private val llmClient = LlmClient()

    /**
     * Analyze a screenshot using the VLM model.
     * Returns a textual description of the screen suitable for the agent loop.
     */
    suspend fun analyzeScreenshot(bitmap: Bitmap, taskGoal: String): String? {
        if (!Preferences.vlmEnabled) return null

        return try {
            val base64Image = compressAndEncode(bitmap)
            Log.d(TAG, "Screenshot encoded: ${base64Image.length} chars")

            // Use the VLM via the OpenAI-compatible vision API
            val prompt = """
You are analyzing an Android phone screen to help complete this task: "$taskGoal"

Describe the screen layout and all interactive elements you can see. For each element, note:
- What it is (button, text field, image, toggle, etc.)
- Any visible text on it
- Its approximate position (top-left, center, bottom-right, etc.)
- Whether it appears tappable/clickable

Format your response as a concise list suitable for an AI agent to decide its next action.
            """.trimIndent()

            llmClient.sendVisionMessage(prompt, base64Image)
        } catch (e: Exception) {
            Log.e(TAG, "VLM analysis failed", e)
            null
        }
    }

    /**
     * Compress and encode bitmap to base64 JPEG.
     */
    private fun compressAndEncode(bitmap: Bitmap): String {
        // Scale down if too large
        val scaled = if (bitmap.width > MAX_IMAGE_DIMENSION || bitmap.height > MAX_IMAGE_DIMENSION) {
            val scale = MAX_IMAGE_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
