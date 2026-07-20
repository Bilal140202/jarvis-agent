package com.jarvis.agent.agent

import android.content.Context
import android.util.Log
import com.jarvis.agent.llm.ActionStep
import com.jarvis.agent.llm.SavedSkill
import com.jarvis.agent.util.Constants
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * Skill Memory — learn and replay successful task sequences.
 *
 * When a task completes successfully, the sequence of atomic actions
 * is saved as a "skill". On future similar tasks, the skill can be
 * replayed without LLM calls, saving tokens and time.
 *
 * Matching uses Jaccard similarity on keyword sets.
 */
class SkillMemory(context: Context) {

    companion object {
        private const val TAG = "SkillMemory"
        private const val FILENAME = "skills_memory.jsonl"
    }

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val skillAdapter = moshi.adapter(SavedSkill::class.java)
    private val skillsFile = File(context.filesDir, FILENAME)

    /**
     * Find a matching skill for the given task.
     * Returns the most similar skill above the Jaccard threshold.
     */
    fun findSkill(taskGoal: String): SavedSkill? {
        val skills = loadAllSkills()
        val goalKeywords = extractKeywords(taskGoal)

        var bestSkill: SavedSkill? = null
        var bestSimilarity = 0.0

        for (skill in skills) {
            val similarity = jaccardSimilarity(goalKeywords, skill.taskKeywords)
            if (similarity > Constants.SKILL_SIMILARITY_THRESHOLD && similarity > bestSimilarity && skill.isReliable) {
                bestSimilarity = similarity
                bestSkill = skill
            }
        }

        if (bestSkill != null) {
            Log.i(TAG, "Found matching skill for '$taskGoal' (similarity: ${"%.2f".format(bestSimilarity)})")
        }
        return bestSkill
    }

    /**
     * Save a new skill or update an existing one.
     */
    fun saveSkill(taskGoal: String, steps: List<ActionStep>) {
        val keywords = extractKeywords(taskGoal)
        val existing = findExactSkill(taskGoal)

        if (existing != null) {
            // Update existing skill
            existing.successCount++
            existing.lastUsed = System.currentTimeMillis()
            updateSkill(existing)
            Log.i(TAG, "Updated existing skill: $taskGoal")
        } else {
            // Create new skill
            val skill = SavedSkill(
                id = "skill_${System.currentTimeMillis()}",
                task = taskGoal,
                taskKeywords = keywords,
                successCount = 1,
                failCount = 0,
                lastUsed = System.currentTimeMillis(),
                steps = steps
            )
            appendSkill(skill)
            Log.i(TAG, "Saved new skill: $taskGoal (${steps.size} steps)")
        }
    }

    /**
     * Update an existing skill's stats.
     */
    fun updateSkill(skill: SavedSkill) {
        val skills = loadAllSkills().toMutableList()
        val index = skills.indexOfFirst { it.id == skill.id }
        if (index >= 0) {
            skills[index] = skill
            writeAllSkills(skills)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────

    private fun findExactSkill(taskGoal: String): SavedSkill? {
        return loadAllSkills().find {
            it.task.equals(taskGoal, ignoreCase = true)
        }
    }

    private fun extractKeywords(text: String): List<String> {
        val stopWords = setOf(
            "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "is", "are", "was", "were", "be", "been",
            "being", "have", "has", "had", "do", "does", "did", "will", "would",
            "could", "should", "may", "might", "can", "shall", "it", "its", "i",
            "me", "my", "we", "our", "you", "your", "he", "she", "they", "them",
            "this", "that", "these", "those", "what", "which", "who", "whom",
            "how", "when", "where", "why", "not", "no", "nor", "so", "if",
            "then", "than", "too", "very", "just", "about", "up", "out", "into",
            "over", "after", "please", "open", "go", "and", "my", "the", "in"
        )
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in stopWords }
            .distinct()
    }

    private fun jaccardSimilarity(setA: List<String>, setB: List<String>): Double {
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val a = setA.toSet()
        val b = setB.toSet()
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return intersection.toDouble() / union.toDouble()
    }

    private fun loadAllSkills(): List<SavedSkill> {
        if (!skillsFile.exists()) return emptyList()
        return try {
            skillsFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line -> skillAdapter.fromJson(line) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load skills", e)
            emptyList()
        }
    }

    private fun appendSkill(skill: SavedSkill) {
        try {
            val json = skillAdapter.toJson(skill)
            skillsFile.appendText("$json\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append skill", e)
        }
    }

    private fun writeAllSkills(skills: List<SavedSkill>) {
        try {
            val lines = skills.mapNotNull { skillAdapter.toJson(it) }
            skillsFile.writeText(lines.joinToString("\n") + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write skills", e)
        }
    }
}
