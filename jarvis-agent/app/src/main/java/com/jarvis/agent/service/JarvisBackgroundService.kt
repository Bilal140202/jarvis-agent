package com.jarvis.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.jarvis.agent.ui.ChatActivity
import com.jarvis.agent.util.Constants
import com.jarvis.agent.util.Preferences

/**
 * Permanent foreground service that keeps Jarvis running in the background.
 * Shows a persistent notification with:
 * - Current status / what the app is doing
 * - Pause button
 * - Stop button
 */
class JarvisBackgroundService : Service() {

    companion object {
        private const val TAG = "JarvisBgService"
        const val ACTION_START = "com.jarvis.agent.BG_START"
        const val ACTION_STOP = "com.jarvis.agent.BG_STOP"
        const val ACTION_PAUSE = "com.jarvis.agent.BG_PAUSE"
        const val ACTION_RESUME = "com.jarvis.agent.BG_RESUME"
        const val ACTION_UPDATE_STATUS = "com.jarvis.agent.BG_UPDATE_STATUS"
        const val EXTRA_STATUS_TEXT = "status_text"

        var isRunning = false
            private set
        var isPaused = false
            private set
        var currentStatus: String = "Ready"
            private set

        fun start(context: Context) {
            try {
                val intent = Intent(context, JarvisBackgroundService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start background service", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, JarvisBackgroundService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop background service", e)
            }
        }

        fun updateStatus(context: Context, status: String) {
            try {
                val intent = Intent(context, JarvisBackgroundService::class.java).apply {
                    action = ACTION_UPDATE_STATUS
                    putExtra(EXTRA_STATUS_TEXT, status)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update status", e)
            }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    Constants.ACTION_TASK_UPDATE, Constants.ACTION_TASK_COMPLETE -> {
                        val msg = intent.getStringExtra(Constants.EXTRA_TASK_MESSAGE) ?: ""
                        val status = intent.getStringExtra(Constants.EXTRA_TASK_STATUS) ?: ""
                        currentStatus = if (status == "success") "Done: $msg" else "Error: $msg"
                        isPaused = false
                        updateNotification()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in statusReceiver", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            isRunning = true
            // Register for task status updates
            val filter = IntentFilter().apply {
                addAction(Constants.ACTION_TASK_UPDATE)
                addAction(Constants.ACTION_TASK_COMPLETE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(statusReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> {
                    currentStatus = "Ready"
                    isPaused = false
                    startForeground(Constants.NOTIFICATION_BG_SERVICE, buildNotification())
                }
                ACTION_STOP -> {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_PAUSE -> {
                    isPaused = true
                    currentStatus = "Paused"
                    updateNotification()
                    // Also cancel any running agent task
                    AgentLoopService.stop(this)
                }
                ACTION_RESUME -> {
                    isPaused = false
                    currentStatus = "Ready"
                    updateNotification()
                }
                ACTION_UPDATE_STATUS -> {
                    val status = intent.getStringExtra(EXTRA_STATUS_TEXT) ?: "Ready"
                    currentStatus = status
                    isPaused = false
                    updateNotification()
                }
                else -> {
                    // Restart with current state
                    startForeground(Constants.NOTIFICATION_BG_SERVICE, buildNotification())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand", e)
        }
        return START_STICKY  // Restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        isRunning = false
        currentStatus = "Stopped"
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(Constants.NOTIFICATION_BG_SERVICE, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, JarvisBackgroundService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, JarvisBackgroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseLabel = if (isPaused) "Resume" else "Pause"
        val pauseIcon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        return Notification.Builder(this, Constants.CHANNEL_BG_SERVICE)
            .setContentTitle("Jarvis Agent")
            .setContentText(currentStatus)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .addAction(pauseIcon, pauseLabel, pauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun createNotificationChannel() {
        try {
            val channel = NotificationChannel(
                Constants.CHANNEL_BG_SERVICE,
                "Jarvis Background",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Jarvis running in the background"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channel", e)
        }
    }
}
