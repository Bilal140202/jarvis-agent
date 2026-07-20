package com.jarvis.agent.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/**
 * App Launcher Service — finds and launches apps by name.
 *
 * Uses PackageManager to resolve app names to package names,
 * then launches them via Intent.
 */
class AppLauncherService(private val context: Context) {

    companion object {
        private const val TAG = "AppLauncher"
    }

    /**
     * Open an app by its display name (e.g., "YouTube", "Chrome").
     * Tries exact match first, then contains match.
     */
    fun openApp(appName: String): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(findPackage(appName) ?: return false)
            ?: return false

        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "Opened app: $appName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app: $appName", e)
            false
        }
    }

    /**
     * Open a URL in the default browser.
     */
    fun openUrl(url: String): Boolean {
        return try {
            val uri = if (url.startsWith("http")) url else "https://$url"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL: $url", e)
            false
        }
    }

    /**
     * Find the package name for an app by display name.
     */
    private fun findPackage(appName: String): String? {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        // Exact match
        val exact = apps.find {
            pm.getApplicationLabel(it).toString().equals(appName, ignoreCase = true)
        }
        if (exact != null) return exact.packageName

        // Contains match
        val contains = apps.find {
            pm.getApplicationLabel(it).toString().contains(appName, ignoreCase = true)
        }
        if (contains != null) return contains.packageName

        // Try package name directly
        try {
            pm.getApplicationInfo(appName, 0)
            return appName
        } catch (_: PackageManager.NameNotFoundException) {}

        Log.w(TAG, "App not found: $appName")
        return null
    }

    /**
     * Get a list of all installed app names.
     */
    fun listApps(): List<String> {
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { pm.getApplicationLabel(it).toString() }
            .sorted()
    }
}
