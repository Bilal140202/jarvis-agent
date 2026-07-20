package com.jarvis.agent.util

/**
 * Application-wide constants.
 */
object Constants {

    const val CHANNEL_AGENT_LOOP = "jarvis_agent_loop"
    const val CHANNEL_OVERLAY = "jarvis_overlay"
    const val CHANNEL_BG_SERVICE = "jarvis_bg_service"
    const val NOTIFICATION_AGENT_LOOP = 1001
    const val NOTIFICATION_OVERLAY = 1002
    const val NOTIFICATION_BG_SERVICE = 1003

    // Broadcast actions
    const val ACTION_TASK_UPDATE = "com.jarvis.agent.TASK_UPDATE"
    const val ACTION_TASK_COMPLETE = "com.jarvis.agent.TASK_COMPLETE"
    const val ACTION_TASK_CANCEL = "com.jarvis.agent.TASK_CANCEL"
    const val EXTRA_TASK_MESSAGE = "task_message"
    const val EXTRA_TASK_STATUS = "task_status"

    // Agent loop action types (Tier 2 - atomic screen actions)
    const val ACTION_CLICK_TEXT = "click_text"
    const val ACTION_CLICK_AT = "click_at"
    const val ACTION_TYPE_TEXT = "type_text"
    const val ACTION_PRESS_ENTER = "press_enter"
    const val ACTION_SCROLL = "scroll"
    const val ACTION_SWIPE = "swipe"
    const val ACTION_PRESS_BACK = "press_back"
    const val ACTION_PRESS_HOME = "press_home"
    const val ACTION_OPEN_APP = "open_app"
    const val ACTION_WAIT = "wait"
    const val ACTION_DONE = "done"

    // Tier 1 - high-level action routing
    const val ROUTE_OPEN_APP = "open_app"
    const val ROUTE_MAKE_CALL = "make_call"
    const val ROUTE_SEND_SMS = "send_sms"
    const val ROUTE_SEARCH_CONTACT = "search_contact"
    const val ROUTE_SET_ALARM = "set_alarm"
    const val ROUTE_SET_VOLUME = "set_volume"
    const val ROUTE_SET_BRIGHTNESS = "set_brightness"
    const val ROUTE_READ_SCREEN = "read_screen"
    const val ROUTE_READ_NOTIFICATIONS = "read_notifications"
    const val ROUTE_PRESS_BACK = "press_back"
    const val ROUTE_EXECUTE_TASK = "execute_task"
    const val ROUTE_GENERAL_QUERY = "general_query"

    // Repeat limits for safety
    const val MAX_PRESS_ENTER_REPEATS = 2
    const val MAX_SCROLL_REPEATS = 3
    const val MAX_SWIPE_REPEATS = 3
    const val MAX_CONSECUTIVE_FAILURES = 5
    const val FAILURE_WARNING_THRESHOLD = 3

    // Delays
    const val ACTION_DELAY_MS_MIN = 1000L
    const val ACTION_DELAY_MS_MAX = 3000L
    const val SCREENSHOT_DELAY_MS = 500L

    // LLM
    const val LLM_TIMEOUT_S = 120L
    const val LLM_MAX_RETRIES = 4
    const val LLM_RETRY_BASE_DELAY_S = 3L

    // Skill memory
    const val SKILL_SIMILARITY_THRESHOLD = 0.6
    const val SKILL_MAX_FAILURE_RATE = 0.3
    const val SKILL_MIN_SUCCESS_COUNT = 1
}
