package com.jarvis.agent.telegram

import android.util.Log
import com.jarvis.agent.agent.ActionHandler
import com.jarvis.agent.llm.LlmClient
import com.jarvis.agent.llm.RouteAction
import com.jarvis.agent.util.Preferences
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit

/**
 * Telegram Bot Service — remote control via Telegram.
 *
 * Long-polls the Telegram Bot API for incoming messages,
 * processes them through the agent, and sends responses back.
 *
 * This allows you to control your phone remotely via Telegram.
 */
class TelegramService {

    companion object {
        private const val TAG = "TelegramService"
        private const val BASE_URL = "https://api.telegram.org/bot"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(35, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private var pollingJob: Job? = null
    private var lastUpdateId = 0L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Start polling for messages.
     */
    fun startPolling(context: android.content.Context) {
        if (!Preferences.telegramEnabled || Preferences.telegramBotToken.isBlank()) {
            Log.w(TAG, "Telegram not configured")
            return
        }

        stopPolling()
        Log.i(TAG, "Starting Telegram polling")

        pollingJob = scope.launch {
            while (isActive) {
                try {
                    pollOnce(context)
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error", e)
                    delay(5000)
                }
            }
        }
    }

    /**
     * Stop polling.
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        Log.i(TAG, "Telegram polling stopped")
    }

    private suspend fun pollOnce(context: android.content.Context) = withContext(Dispatchers.IO) {
        val token = Preferences.telegramBotToken
        val url = "$BASE_URL$token/getUpdates?offset=${lastUpdateId + 1}&timeout=30"

        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext

        try {
            val json = org.json.JSONObject(body)
            val ok = json.optBoolean("ok", false)
            if (!ok) return@withContext

            val results = json.optJSONArray("result") ?: return@withContext
            for (i in 0 until results.length()) {
                val update = results.getJSONObject(i)
                lastUpdateId = update.optLong("update_id", lastUpdateId)

                val message = update.optJSONObject("message") ?: continue
                val text = message.optString("text", "")
                val chatId = message.optJSONObject("chat")?.optLong("id", 0) ?: continue

                if (text.isNotBlank() && chatId > 0) {
                    processTelegramMessage(text, chatId, context)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Telegram update", e)
        }
    }

    private suspend fun processTelegramMessage(text: String, chatId: Long, context: android.content.Context) {
        Log.i(TAG, "Telegram message: $text")

        // Route through LLM and execute
        val llmClient = LlmClient()
        val routeAction = llmClient.sendRouteMessage(text)
        val responseText = if (routeAction != null) {
            val actionHandler = ActionHandler(context)
            val result = actionHandler.execute(routeAction)
            result.details ?: if (result.success) "Done!" else "Failed"
        } else {
            "I couldn't process that request."
        }

        // Send response back to Telegram
        sendMessage(chatId, responseText)
    }

    private fun sendMessage(chatId: Long, text: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val token = Preferences.telegramBotToken
                val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
                val url = "$BASE_URL$token/sendMessage?chat_id=$chatId&text=$encodedText"
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send Telegram message", e)
            }
        }
    }
}
