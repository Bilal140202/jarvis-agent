package com.jarvis.agent.llm

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Action models for the two-tier action architecture.
 *
 * Tier 1 (route): High-level actions from the initial LLM routing call.
 *   e.g., open_app, make_call, send_sms, execute_task
 *
 * Tier 2 (atomic): Low-level screen actions from the agent loop.
 *   e.g., click_text, type_text, scroll, press_back, done
 */

// ═══════════════════════════════════════════════════════════
// TIER 1 — Route Action (initial LLM response)
// ═══════════════════════════════════════════════════════════

@JsonClass(generateAdapter = true)
data class RouteAction(
    @Json(name = "action") val action: String,
    @Json(name = "params") val params: Map<String, Any> = emptyMap(),
    @Json(name = "response") val response: String = ""
) {
    companion object {
        val AVAILABLE_ACTIONS = listOf(
            "open_app", "make_call", "send_sms", "search_contact",
            "set_alarm", "set_volume", "set_brightness",
            "read_screen", "read_notifications", "press_back",
            "execute_task", "general_query"
        )
    }
}

@JsonClass(generateAdapter = true)
data class RouteActionResult(
    @Json(name = "action_type") val actionType: String,
    @Json(name = "success") val success: Boolean,
    @Json(name = "details") val details: String? = null
)

// ═══════════════════════════════════════════════════════════
// TIER 2 — Atomic Action (agent loop step)
// ═══════════════════════════════════════════════════════════

@JsonClass(generateAdapter = true)
data class AtomicAction(
    @Json(name = "action") val action: String,
    @Json(name = "params") val params: Map<String, Any> = emptyMap(),
    @Json(name = "reasoning") val reasoning: String = "",
    @Json(name = "is_complete") val isComplete: Boolean = false
) {
    /** Extract string param with fallback. */
    fun param(key: String): String? = params[key]?.toString()

    /** Extract int param with fallback. */
    fun intParam(key: String): Int? = params[key]?.let {
        when (it) {
            is Number -> it.toInt()
            is String -> it.toIntOrNull()
            else -> null
        }
    }
}

/**
 * A recorded step in a skill (for skill memory).
 */
@JsonClass(generateAdapter = true)
data class ActionStep(
    @Json(name = "action") val action: String,
    @Json(name = "params") val params: Map<String, Any> = emptyMap()
)

/**
 * A saved skill — a previously successful task sequence that can be replayed.
 */
@JsonClass(generateAdapter = true)
data class SavedSkill(
    @Json(name = "id") val id: String,
    @Json(name = "task") val task: String,
    @Json(name = "task_keywords") val taskKeywords: List<String>,
    @Json(name = "success_count") var successCount: Int = 0,
    @Json(name = "fail_count") var failCount: Int = 0,
    @Json(name = "last_used") var lastUsed: Long = System.currentTimeMillis(),
    @Json(name = "steps") val steps: List<ActionStep>
) {
    val isReliable: Boolean
        get() = successCount >= 1 && (failCount.toFloat() / (successCount + failCount)) < 0.3f
}

// ═══════════════════════════════════════════════════════════
// LLM API Models
// ═══════════════════════════════════════════════════════════

@JsonClass(generateAdapter = true)
data class ChatMessage(
    @Json(name = "role") val role: String,
    @Json(name = "content") val content: String
)

@JsonClass(generateAdapter = true)
data class ChatRequest(
    @Json(name = "model") val model: String,
    @Json(name = "messages") val messages: List<ChatMessage>,
    @Json(name = "temperature") val temperature: Double = 0.3,
    @Json(name = "max_tokens") val maxTokens: Int = 2048,
    @Json(name = "stream") val stream: Boolean = false
)

@JsonClass(generateAdapter = true)
data class ChatResponse(
    @Json(name = "choices") val choices: List<Choice> = emptyList(),
    @Json(name = "usage") val usage: Usage? = null
)

@JsonClass(generateAdapter = true)
data class Choice(
    @Json(name = "message") val message: ChatMessage,
    @Json(name = "finish_reason") val finishReason: String? = null
)

@JsonClass(generateAdapter = true)
data class Usage(
    @Json(name = "prompt_tokens") val promptTokens: Int = 0,
    @Json(name = "completion_tokens") val completionTokens: Int = 0,
    @Json(name = "total_tokens") val totalTokens: Int = 0
)
