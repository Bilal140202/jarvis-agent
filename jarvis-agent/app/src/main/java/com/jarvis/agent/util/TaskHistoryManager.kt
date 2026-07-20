package com.jarvis.agent.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Manages task history with star/save logic:
 * - Every task is saved for that day
 * - If user stars a task, it's saved forever (until deleted)
 * - If not starred, it disappears after 24 hours OR when a 6th unstarred task is added (max 5 unstarred)
 * - Starred tasks are permanent in task history
 */
object TaskHistoryManager {

    private const val PREFS_NAME = "jarvis_task_history"
    private const val KEY_TASKS = "tasks"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * A single task record.
     */
    data class TaskRecord(
        val id: String,
        val text: String,
        val result: String,
        val timestamp: Long,
        val isStarred: Boolean,
        val dateKey: String  // yyyy-MM-dd for day grouping
    )

    /**
     * Save a new task. Automatically enforces the 5-unstarred limit.
     */
    fun saveTask(text: String, result: String): TaskRecord {
        val now = System.currentTimeMillis()
        val dateKey = android.text.format.DateFormat.format("yyyy-MM-dd", now).toString()

        val record = TaskRecord(
            id = UUID.randomUUID().toString(),
            text = text,
            result = result,
            timestamp = now,
            isStarred = false,
            dateKey = dateKey
        )

        val tasks = getAllTasks().toMutableList()
        tasks.add(0, record)  // newest first

        // Enforce limits: max 5 unstarred tasks
        enforceUnstarredLimit(tasks)

        saveAll(tasks)
        return record
    }

    /**
     * Star a task — makes it permanent.
     */
    fun starTask(taskId: String) {
        val tasks = getAllTasks().toMutableList()
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index >= 0) {
            tasks[index] = tasks[index].copy(isStarred = true)
            saveAll(tasks)
        }
    }

    /**
     * Unstar a task — it becomes subject to the 5-limit and 24-hour expiry again.
     */
    fun unstarTask(taskId: String) {
        val tasks = getAllTasks().toMutableList()
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index >= 0) {
            tasks[index] = tasks[index].copy(isStarred = false)
            // Re-enforce limits since this task is now unstarred
            enforceUnstarredLimit(tasks)
            saveAll(tasks)
        }
    }

    /**
     * Delete a specific task by ID.
     */
    fun deleteTask(taskId: String) {
        val tasks = getAllTasks().filter { it.id != taskId }
        saveAll(tasks)
    }

    /**
     * Get all tasks (starred + unstarred that haven't expired).
     */
    fun getAllTasks(): List<TaskRecord> {
        val json = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                try {
                    val obj = array.getJSONObject(i)
                    TaskRecord(
                        id = obj.getString("id"),
                        text = obj.getString("text"),
                        result = obj.optString("result", ""),
                        timestamp = obj.getLong("timestamp"),
                        isStarred = obj.getBoolean("isStarred"),
                        dateKey = obj.getString("dateKey")
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get only today's tasks (for the "today" view).
     */
    fun getTodayTasks(): List<TaskRecord> {
        val todayKey = android.text.format.DateFormat.format("yyyy-MM-dd", System.currentTimeMillis()).toString()
        return getAllTasks().filter { it.dateKey == todayKey || it.isStarred }
    }

    /**
     * Get only starred (permanent) tasks.
     */
    fun getStarredTasks(): List<TaskRecord> {
        return getAllTasks().filter { it.isStarred }
    }

    /**
     * Cleanup expired unstarred tasks (24-hour expiry).
     * Should be called periodically (e.g., on app start).
     */
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val twentyFourHours = 24 * 60 * 60 * 1000L
        val tasks = getAllTasks().toMutableList()

        val before = tasks.size
        tasks.removeAll { !it.isStarred && (now - it.timestamp) > twentyFourHours }

        if (tasks.size != before) {
            saveAll(tasks)
        }
    }

    /**
     * Enforce the 5-unstarred task limit.
     * Removes oldest unstarred tasks that exceed the limit.
     */
    private fun enforceUnstarredLimit(tasks: MutableList<TaskRecord>) {
        val unstarred = tasks.filter { !it.isStarred }
        if (unstarred.size > 5) {
            // Sort unstarred by timestamp ascending (oldest first)
            val sorted = unstarred.sortedBy { it.timestamp }
            val toRemove = sorted.take(unstarred.size - 5).map { it.id }.toSet()
            tasks.removeAll { it.id in toRemove }
        }
    }

    /**
     * Toggle star status of a task.
     */
    fun toggleStar(taskId: String) {
        val tasks = getAllTasks().toMutableList()
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index >= 0) {
            val wasStarred = tasks[index].isStarred
            tasks[index] = tasks[index].copy(isStarred = !wasStarred)
            if (wasStarred) {
                // Unstarring — enforce limits again
                enforceUnstarredLimit(tasks)
            }
            saveAll(tasks)
        }
    }

    private fun saveAll(tasks: List<TaskRecord>) {
        try {
            val array = JSONArray()
            tasks.forEach { record ->
                val obj = JSONObject().apply {
                    put("id", record.id)
                    put("text", record.text)
                    put("result", record.result)
                    put("timestamp", record.timestamp)
                    put("isStarred", record.isStarred)
                    put("dateKey", record.dateKey)
                }
                array.put(obj)
            }
            prefs.edit().putString(KEY_TASKS, array.toString()).apply()
        } catch (e: Exception) {
            // Silently fail — task history is non-critical
        }
    }
}
