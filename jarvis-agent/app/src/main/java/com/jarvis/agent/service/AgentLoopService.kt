package com.jarvis.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.jarvis.agent.agent.ActionHandler
import com.jarvis.agent.llm.LlmClient
import com.jarvis.agent.ui.ChatActivity
import com.jarvis.agent.util.Constants
import com.jarvis.agent.util.Preferences
import kotlinx.coroutines.*

/**
 * Foreground Service that runs the agent loop.
 */
class AgentLoopService : Service() {

    companion object {
        private const val TAG = "AgentLoopService"
        const val ACTION_START_TASK = "com.jarvis.agent.START_TASK"
        const val ACTION_CANCEL_TASK = "com.jarvis.agent.CANCEL_TASK"
        const val EXTRA_TASK_GOAL = "task_goal"

        var isRunning = false
            private set
        var currentTask: String? = null
            private set

        fun start(context: Context, goal: String) {
            try {
                val intent = Intent(context, AgentLoopService::class.java).apply {
                    action = ACTION_START_TASK
                    putExtra(EXTRA_TASK_GOAL, goal)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AgentLoopService", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AgentLoopService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop AgentLoopService", e)
            }
        }
    }

    private val scope = CoroutineService(Dispatchers.Main)
    private var taskJob: Job? = null
    private val llmClient = LlmClient()

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            isRunning = true
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START_TASK -> {
                    val goal = intent.getStringExtra(EXTRA_TASK_GOAL) ?: return START_NOT_STICKY
                    currentTask = goal
                    startForeground(Constants.NOTIFICATION_AGENT_LOOP, buildNotification("Running: $goal"))
                    executeTask(goal)
                }
                ACTION_CANCEL_TASK -> {
                    taskJob?.cancel()
                    stopSelf()
                }
                else -> {
                    // Unknown or null intent — stop immediately to avoid ANR
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        taskJob?.cancel()
        isRunning = false
        currentTask = null
    }

    private fun executeTask(goal: String) {
        taskJob = scope.launch {
            try {
                updateNotification("Analyzing: $goal")

                val routeAction = llmClient.sendRouteMessage(goal)
                if (routeAction == null) {
                    broadcastResult("Failed to understand the request", false)
                    stopSelf()
                    return@launch
                }

                updateNotification("Executing: ${routeAction.action}")

                val actionHandler = ActionHandler(this@AgentLoopService)
                val result = actionHandler.execute(routeAction)

                broadcastResult(result.details ?: "Done", result.success)
            } catch (e: CancellationException) {
                broadcastResult("Task cancelled", false)
            } catch (e: Exception) {
                Log.e(TAG, "Task execution failed", e)
                broadcastResult("Error: ${e.message}", false)
            } finally {
                stopSelf()
            }
        }
    }

    private fun broadcastResult(message: String, success: Boolean) {
        try {
            val intent = Intent(if (success) Constants.ACTION_TASK_COMPLETE else Constants.ACTION_TASK_UPDATE).apply {
                putExtra(Constants.EXTRA_TASK_MESSAGE, message)
                putExtra(Constants.EXTRA_TASK_STATUS, if (success) "success" else "error")
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast result", e)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(Constants.NOTIFICATION_AGENT_LOOP, buildNotification(text))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ChatActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AgentLoopService::class.java).apply { action = ACTION_CANCEL_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, Constants.CHANNEL_AGENT_LOOP)
            .setContentTitle("Jarvis Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        try {
            val channel = NotificationChannel(
                Constants.CHANNEL_AGENT_LOOP,
                "Agent Tasks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Jarvis is executing a task"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channel", e)
        }
    }

    private class CoroutineService(dispatcher: CoroutineDispatcher) : CoroutineScope {
        override val coroutineContext = dispatcher + SupervisorJob()
    }
}
