package com.jarvis.agent.agent

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.agent.llm.*
import com.jarvis.agent.service.AgentAccessibilityService
import com.jarvis.agent.service.AppLauncherService
import com.jarvis.agent.service.CommunicationService
import com.jarvis.agent.service.SystemControlService
import com.jarvis.agent.util.Constants
import com.jarvis.agent.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Action Handler — routes parsed RouteActions to the appropriate service.
 *
 * This is the Tier 1 dispatcher. It takes the LLM's routing decision
 * and delegates to the correct service for execution.
 */
class ActionHandler(private val context: Context) {

    private val tag = "ActionHandler"
    private val llmClient = LlmClient()
    private val appLauncher = AppLauncherService(context)
    private val communication = CommunicationService(context)
    private val systemControl = SystemControlService(context)

    /**
     * Execute a routed action and return the result.
     */
    suspend fun execute(action: RouteAction): RouteActionResult {
        Log.i(tag, "Executing route action: ${action.action} with params: ${action.params}")

        return when (action.action) {
            Constants.ROUTE_OPEN_APP -> {
                val appName = action.params["app_name"]?.toString() ?: ""
                val success = appLauncher.openApp(appName)
                RouteActionResult(action.action, success, if (success) "Opened $appName" else "Could not open $appName")
            }

            Constants.ROUTE_MAKE_CALL -> {
                val contactName = action.params["contact_name"]?.toString()
                val phoneNumber = action.params["phone_number"]?.toString()
                val result = if (!phoneNumber.isNullOrBlank()) {
                    communication.makeCall(phoneNumber)
                } else if (!contactName.isNullOrBlank()) {
                    communication.makeCallByName(contactName)
                } else {
                    false
                }
                RouteActionResult(action.action, result, if (result) "Calling..." else "Could not make call")
            }

            Constants.ROUTE_SEND_SMS -> {
                val contactName = action.params["contact_name"]?.toString() ?: ""
                val message = action.params["message"]?.toString() ?: ""
                val result = communication.sendSms(contactName, message)
                RouteActionResult(action.action, result, if (result) "SMS sent" else "Could not send SMS")
            }

            Constants.ROUTE_SEARCH_CONTACT -> {
                val name = action.params["name"]?.toString() ?: ""
                val contacts = communication.searchContacts(name)
                val details = contacts.joinToString("\n") { "${it.name}: ${it.phoneNumber}" }
                RouteActionResult(action.action, true, details.ifBlank { "No contacts found for '$name'" })
            }

            Constants.ROUTE_SET_ALARM -> {
                val hour = action.params["hour"]?.toString()?.toIntOrNull()
                val minute = action.params["minute"]?.toString()?.toIntOrNull() ?: 0
                val label = action.params["label"]?.toString() ?: "Jarvis Alarm"
                val timerMinutes = action.params["timer_minutes"]?.toString()?.toIntOrNull()

                val result = if (timerMinutes != null) {
                    systemControl.setTimer(timerMinutes, label)
                } else if (hour != null) {
                    systemControl.setAlarm(hour, minute, label)
                } else {
                    false
                }
                RouteActionResult(action.action, result, if (result) "Alarm set" else "Could not set alarm")
            }

            Constants.ROUTE_SET_VOLUME -> {
                val level = action.params["level"]?.toString()?.toIntOrNull() ?: 50
                val result = systemControl.setVolume(level)
                RouteActionResult(action.action, result, if (result) "Volume set to $level%" else "Could not set volume")
            }

            Constants.ROUTE_SET_BRIGHTNESS -> {
                val level = action.params["level"]?.toString()?.toIntOrNull() ?: 70
                val result = systemControl.setBrightness(level)
                RouteActionResult(action.action, result, if (result) "Brightness set to $level%" else "Could not set brightness")
            }

            Constants.ROUTE_READ_SCREEN -> {
                val service = AgentAccessibilityService.instance
                if (service == null) {
                    RouteActionResult(action.action, false, "Accessibility service not running")
                } else {
                    val desc = service.getScreenDescription(compressed = Preferences.useScreenCompression)
                    RouteActionResult(action.action, true, desc)
                }
            }

            Constants.ROUTE_READ_NOTIFICATIONS -> {
                val service = AgentAccessibilityService.instance
                if (service == null) {
                    RouteActionResult(action.action, false, "Accessibility service not running")
                } else {
                    service.openNotifications()
                    kotlinx.coroutines.delay(1000)
                    val desc = service.getScreenDescription(compressed = true)
                    service.pressBack()
                    RouteActionResult(action.action, true, desc)
                }
            }

            Constants.ROUTE_PRESS_BACK -> {
                val service = AgentAccessibilityService.instance
                val result = service?.pressBack() ?: false
                RouteActionResult(action.action, result, if (result) "Back pressed" else "Could not press back")
            }

            Constants.ROUTE_EXECUTE_TASK -> {
                val goal = action.params["goal"]?.toString() ?: action.params["task"]?.toString() ?: ""
                if (goal.isBlank()) {
                    RouteActionResult(action.action, false, "No task goal specified")
                } else {
                    val executor = TaskExecutor(context, llmClient)
                    val result = executor.executeTask(goal)
                    RouteActionResult(action.action, result.success, result.details)
                }
            }

            Constants.ROUTE_GENERAL_QUERY -> {
                val answer = llmClient.sendGeneralQuery(action.response.ifBlank { action.params["question"]?.toString() ?: "" })
                RouteActionResult(action.action, true, answer ?: "Could not generate response")
            }

            else -> {
                Log.w(tag, "Unknown action: ${action.action}")
                RouteActionResult(action.action, false, "Unknown action: ${action.action}")
            }
        }
    }
}
