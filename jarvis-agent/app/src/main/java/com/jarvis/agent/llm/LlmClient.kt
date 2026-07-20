package com.jarvis.agent.llm

import android.util.Log
import com.jarvis.agent.util.Preferences
import com.jarvis.agent.util.Constants
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * LLM Client — communicates with any OpenAI-compatible API.
 *
 * Supports: OpenAI, DeepSeek, OpenRouter, NVIDIA NIM, or any compatible endpoint.
 * Handles:
 * - Request construction with system prompts
 * - <think> block stripping (for reasoning models)
 * - Truncated JSON auto-repair
 * - Retry with exponential backoff
 * - Structured JSON action parsing
 */
class LlmClient {

    companion object {
        private const val TAG = "LlmClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
        private val chatRequestAdapter = moshi.adapter(ChatRequest::class.java)
        private val chatResponseAdapter = moshi.adapter(ChatResponse::class.java)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(Constants.LLM_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(Constants.LLM_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(Constants.LLM_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    // ═══════════════════════════════════════════════════════════
    // SYSTEM PROMPTS
    // ═══════════════════════════════════════════════════════════

    /**
     * Tier 1 System Prompt — initial action routing.
     * The LLM decides which high-level action to take.
     */
    private val routeSystemPrompt = """
You are Jarvis, an intelligent Android phone assistant. You help users control their phone through natural language.

Based on the user's request, choose the best action from these available actions:

1. **open_app** - Open an application by name. Params: {"app_name": "YouTube"}
2. **make_call** - Make a phone call. Params: {"contact_name": "John"} or {"phone_number": "+1234567890"}
3. **send_sms** - Send a text message. Params: {"contact_name": "John", "message": "Hello"}
4. **search_contact** - Look up a contact. Params: {"name": "John"}
5. **set_alarm** - Set an alarm. Params: {"hour": 7, "minute": 30, "label": "Wake up"} or {"timer_minutes": 15}
6. **set_volume** - Set media volume. Params: {"level": 50} (0-100)
7. **set_brightness** - Set screen brightness. Params: {"level": 70} (0-100)
8. **read_screen** - Read what's currently on screen. No params needed.
9. **read_notifications** - Read pending notifications. No params needed.
10. **press_back** - Press the back button. No params needed.
11. **execute_task** - For complex multi-step tasks that require navigating the UI. Params: {"goal": "Open YouTube and search for cats"}
12. **general_query** - Answer a general knowledge question. No specific params.

IMPORTANT: Return ONLY a JSON object in this exact format:
```json
{"action": "action_name", "params": {...}, "response": "Brief user-facing message"}
```

Use **execute_task** for anything requiring multiple UI steps like "open X and do Y", "navigate to settings and toggle Z", "search for X in app Y".
Use **general_query** for questions that don't require phone actions.
For simple single actions, use the specific action directly.
    """.trimIndent()

    /**
     * Tier 2 System Prompt — agent loop for multi-step UI automation.
     */
    private val taskSystemPrompt = """
You are Jarvis, controlling an Android phone through its accessibility interface. You can see the screen's UI elements and perform actions.

You must complete the given task by choosing one action per step. After each action, you'll see the updated screen.

Available actions (return as JSON):
1. **click_text** - Click an element by its visible text. Params: {"text": "Settings"}
2. **click_at** - Click at specific coordinates (percentage of screen 0-100). Params: {"x": 50, "y": 30}
3. **type_text** - Type text into a focused editable field. Params: {"text": "hello", "field_hint": "Search"}
4. **press_enter** - Press Enter/Submit. No params.
5. **scroll** - Scroll the screen. Params: {"direction": "down"} or "up"
6. **swipe** - Swipe gesture. Params: {"start_x": 50, "start_y": 70, "end_x": 50, "end_y": 30}
7. **press_back** - Press the Back button. No params.
8. **press_home** - Press the Home button. No params.
9. **open_app** - Open an app by name. Params: {"app_name": "YouTube"}
10. **wait** - Wait 1 second for screen to update. No params.
11. **done** - Task is complete. Params: {"reasoning": "Task completed successfully"}

IMPORTANT: Return ONLY a JSON object:
```json
{"action": "action_name", "params": {...}, "reasoning": "Brief explanation", "is_complete": false}
```

Set "is_complete" to true ONLY when the task is fully done and "action" to "done".

Rules:
- Read the screen carefully before each action
- If an action fails, try a different approach
- Use "wait" if the screen might be loading
- Use "press_back" if you're stuck on a wrong screen
- Be precise with click_text — match the exact visible text
- For scrolling, prefer "scroll" over "swipe" as it's more reliable
    """.trimIndent()

    /**
     * Chat mode system prompt — plain conversational AI.
     */
    private val chatSystemPrompt = """
You are Jarvis, a helpful and friendly AI assistant built into an Android phone. You can help with questions, advice, explanations, and general conversation. Be concise but thorough.
    """.trimIndent()

    // ═══════════════════════════════════════════════════════════
    // API CALLS
    // ═══════════════════════════════════════════════════════════

    /**
     * Send a route-mode message (Tier 1) to get initial action routing.
     */
    suspend fun sendRouteMessage(userMessage: String): RouteAction? = withContext(Dispatchers.IO) {
        val messages = listOf(
            ChatMessage(role = "system", content = routeSystemPrompt),
            ChatMessage(role = "user", content = userMessage)
        )
        val response = callApi(messages) ?: return@withContext null
        parseRouteAction(response)
    }

    /**
     * Send a task-mode message (Tier 2) — single step in the agent loop.
     */
    suspend fun sendTaskMessage(
        userGoal: String,
        screenContent: String,
        previousResult: String,
        step: Int,
        maxSteps: Int,
        failureWarning: String = ""
    ): AtomicAction? = withContext(Dispatchers.IO) {
        val prompt = buildString {
            appendLine("TASK: $userGoal")
            appendLine()
            appendLine("CURRENT SCREEN TEXT DUMP:")
            appendLine(screenContent)
            appendLine()
            appendLine("PREVIOUS ACTION RESULT: $previousResult")
            appendLine()
            if (failureWarning.isNotEmpty()) {
                appendLine("WARNING: $failureWarning")
                appendLine()
            }
            appendLine("Step $step/$maxSteps. What is the next action?")
        }

        val messages = listOf(
            ChatMessage(role = "system", content = taskSystemPrompt),
            ChatMessage(role = "user", content = prompt)
        )

        val response = callApi(messages) ?: return@withContext null
        parseAtomicAction(response)
    }

    /**
     * Send a chat-mode message (conversational, no actions).
     */
    suspend fun sendChatMessage(
        userMessage: String,
        history: List<ChatMessage> = emptyList()
    ): String? = withContext(Dispatchers.IO) {
        val messages = mutableListOf(ChatMessage(role = "system", content = chatSystemPrompt))
        // Keep last 20 messages from history
        messages.addAll(history.takeLast(20))
        messages.add(ChatMessage(role = "user", content = userMessage))

        callApi(messages)
    }

    /**
     * Send a general query response.
     */
    suspend fun sendGeneralQuery(userMessage: String): String? = withContext(Dispatchers.IO) {
        val messages = listOf(
            ChatMessage(role = "system", content = "You are Jarvis, a helpful AI assistant. Answer the user's question directly and concisely."),
            ChatMessage(role = "user", content = userMessage)
        )
        callApi(messages)
    }

    /**
     * Send a vision message with a base64-encoded image to a VLM model.
     * Uses OpenAI-compatible vision API format.
     */
    suspend fun sendVisionMessage(prompt: String, base64Image: String): String? = withContext(Dispatchers.IO) {
        val vlmModel = Preferences.vlmModel.ifBlank { Preferences.model }
        val userContent = """[
            {"type":"text","text":${"\""}$prompt${"\""}},
            {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,$base64Image"}}
        ]""".trimIndent()

        // Build raw JSON for vision request
        val requestJson = """{
            "model":"$vlmModel",
            "messages":[
                {"role":"system","content":"You are a visual assistant that analyzes Android phone screenshots."},
                {"role":"user","content":$userContent}
            ],
            "temperature":${Preferences.temperature.toDouble()},
            "max_tokens":${Preferences.maxTokens}
        }""".trimIndent()

        val url = "${Preferences.baseUrl.trimEnd('/')}/chat/completions"
        val httpRequest = okhttp3.Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${Preferences.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toRequestBody(JSON_MEDIA))
            .build()

        try {
            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()
            if (!response.isSuccessful || responseBody.isNullOrBlank()) return@withContext null
            val chatResponse = chatResponseAdapter.fromJson(responseBody)
            val content = chatResponse?.choices?.firstOrNull()?.message?.content
            if (content.isNullOrBlank()) return@withContext null
            stripThinkBlocks(content)
        } catch (e: Exception) {
            Log.e(TAG, "Vision API call failed", e)
            null
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CORE API CALL (with retry)
    // ═══════════════════════════════════════════════════════════

    /**
     * Make the actual HTTP call to the LLM API.
     * Includes retry with exponential backoff.
     */
    private suspend fun callApi(messages: List<ChatMessage>, retries: Int = 0): String? {
        return try {
            val request = ChatRequest(
                model = Preferences.model,
                messages = messages,
                temperature = Preferences.temperature.toDouble(),
                maxTokens = Preferences.maxTokens
            )
            val json = chatRequestAdapter.toJson(request)
            val url = "${Preferences.baseUrl.trimEnd('/')}/chat/completions"

            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${Preferences.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(json.toRequestBody(JSON_MEDIA))
                .build()

            val response = client.newCall(httpRequest).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody.isNullOrBlank()) {
                Log.e(TAG, "API error: ${response.code} - ${responseBody?.take(500)}")
                throw Exception("API returned ${response.code}")
            }

            val chatResponse = chatResponseAdapter.fromJson(responseBody)
            val content = chatResponse?.choices?.firstOrNull()?.message?.content

            if (content.isNullOrBlank()) {
                Log.e(TAG, "Empty response content")
                throw Exception("Empty response")
            }

            // Strip <think> blocks (reasoning models)
            val cleaned = stripThinkBlocks(content)
            Log.d(TAG, "LLM response: ${cleaned.take(200)}")
            cleaned

        } catch (e: Exception) {
            Log.e(TAG, "API call failed (attempt ${retries + 1}/${Constants.LLM_MAX_RETRIES})", e)
            if (retries < Constants.LLM_MAX_RETRIES - 1) {
                val delayMs = Constants.LLM_RETRY_BASE_DELAY_S * 1000L * (retries + 1)
                delay(delayMs)
                callApi(messages, retries + 1)
            } else {
                null
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PARSING
    // ═══════════════════════════════════════════════════════════

    private fun parseRouteAction(response: String): RouteAction? {
        return try {
            val json = extractJson(response)
            moshi.adapter(RouteAction::class.java).fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse RouteAction: ${e.message}")
            Log.d(TAG, "Raw response: ${response.take(500)}")
            // Fallback: treat as general query
            RouteAction(action = "general_query", params = emptyMap(), response = response)
        }
    }

    private fun parseAtomicAction(response: String): AtomicAction? {
        return try {
            val json = extractJson(response)
            moshi.adapter(AtomicAction::class.java).fromJson(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse AtomicAction: ${e.message}")
            null
        }
    }

    /**
     * Extract JSON object from LLM response.
     * Handles: markdown code blocks, extra text before/after JSON, truncated JSON.
     */
    private fun extractJson(text: String): String {
        var cleaned = text.trim()

        // Remove markdown code blocks
        cleaned = cleaned.replace(Regex("```(?:json)?\\s*"), "").replace("```", "").trim()

        // Try to find JSON object boundaries
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')

        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1)
        }

        // Auto-repair truncated JSON
        var openBraces = 0
        var openBrackets = 0
        for (c in cleaned) {
            when (c) {
                '{' -> openBraces++
                '}' -> openBraces--
                '[' -> openBrackets++
                ']' -> openBrackets--
            }
        }
        while (openBrackets > 0) { cleaned += "]"; openBrackets-- }
        while (openBraces > 0) { cleaned += "}"; openBraces-- }

        return cleaned
    }

    /**
     * Strip <think>...</think> blocks from reasoning model outputs.
     */
    private fun stripThinkBlocks(text: String): String {
        return text.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
    }

    /**
     * Fetch available models from the API endpoint.
     */
    suspend fun fetchAvailableModels(): List<String> = withContext(Dispatchers.IO) {
        try {
            val url = "${Preferences.baseUrl.trimEnd('/')}/models"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${Preferences.apiKey}")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()

            // Parse the models list from the response
            val models = mutableListOf<String>()
            val regex = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
            regex.findAll(body).forEach { match ->
                models.add(match.groupValues[1])
            }
            models.sorted()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch models", e)
            emptyList()
        }
    }
}
