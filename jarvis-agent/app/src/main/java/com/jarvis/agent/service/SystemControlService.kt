package com.jarvis.agent.service

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log

/**
 * System Control Service — volume, brightness, alarms.
 */
class SystemControlService(private val context: Context) {

    companion object {
        private const val TAG = "SystemControl"
    }

    /**
     * Set media volume as a percentage (0-100).
     */
    fun setVolume(levelPercent: Int): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val volume = (maxVolume * levelPercent / 100).coerceIn(0, maxVolume)
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, volume, 0)
            Log.i(TAG, "Volume set to $levelPercent% (actual: $volume/$maxVolume)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Set volume failed", e)
            false
        }
    }

    /**
     * Set screen brightness as a percentage (0-100).
     * Requires WRITE_SETTINGS permission.
     */
    fun setBrightness(levelPercent: Int): Boolean {
        return try {
            if (!Settings.System.canWrite(context)) {
                Log.w(TAG, "WRITE_SETTINGS permission not granted")
                return false
            }
            val brightness = (levelPercent * 255 / 100).coerceIn(0, 255)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )
            // Also set brightness mode to manual
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Log.i(TAG, "Brightness set to $levelPercent% (actual: $brightness)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Set brightness failed", e)
            false
        }
    }

    /**
     * Set an alarm for a specific time.
     */
    fun setAlarm(hour: Int, minute: Int, label: String = "Jarvis Alarm"): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Alarm set for $hour:$minute")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Set alarm failed", e)
            false
        }
    }

    /**
     * Set a countdown timer.
     */
    fun setTimer(minutes: Int, label: String = "Jarvis Timer"): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60L)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Timer set for $minutes minutes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Set timer failed", e)
            false
        }
    }
}
