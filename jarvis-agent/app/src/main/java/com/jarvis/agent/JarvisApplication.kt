package com.jarvis.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.jarvis.agent.util.Constants
import com.jarvis.agent.util.Preferences

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Preferences.init(this)
            createNotificationChannels()
        } catch (e: Exception) {
            Log.e("JarvisApp", "Failed to initialize", e)
        }
    }

    private fun createNotificationChannels() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val agentChannel = NotificationChannel(
                Constants.CHANNEL_AGENT_LOOP,
                "Agent Tasks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Jarvis is executing a task"
            }

            val overlayChannel = NotificationChannel(
                Constants.CHANNEL_OVERLAY,
                "Overlay",
                NotificationManager.IMPORTANCE_MIN
            )

            nm.createNotificationChannel(agentChannel)
            nm.createNotificationChannel(overlayChannel)
        } catch (e: Exception) {
            Log.e("JarvisApp", "Failed to create notification channels", e)
        }
    }
}
