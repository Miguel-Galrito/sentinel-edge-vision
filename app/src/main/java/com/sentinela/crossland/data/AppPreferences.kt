package com.sentinela.crossland.data

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("sentinela_crossland_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ROI_LEFT = "roi_left"
        private const val KEY_ROI_TOP = "roi_top"
        private const val KEY_ROI_RIGHT = "roi_right"
        private const val KEY_ROI_BOTTOM = "roi_bottom"
        private const val KEY_MOTION_SENSITIVITY = "motion_sensitivity"
        private const val KEY_OLED_ECO_MODE = "oled_eco_mode"
        private const val KEY_AUDIO_ALARM_ENABLED = "audio_alarm_enabled"
        private const val KEY_TOTAL_DETECTIONS = "total_detections"
        private const val KEY_CALIBRATION_MODE = "calibration_mode"
        private const val KEY_ALARM_COOLDOWN_SECONDS = "alarm_cooldown_seconds"
        private const val KEY_SNOOZE_UNTIL_TIMESTAMP = "snooze_until_timestamp"
        private const val KEY_ALLOW_BACKGROUND_SURVEILLANCE = "allow_background_surveillance"
    }

    var roi: NormalizedRect
        get() {
            val left = prefs.getFloat(KEY_ROI_LEFT, 0.05f)
            val top = prefs.getFloat(KEY_ROI_TOP, 0.35f)
            val right = prefs.getFloat(KEY_ROI_RIGHT, 0.95f)
            val bottom = prefs.getFloat(KEY_ROI_BOTTOM, 0.85f)
            return NormalizedRect(left, top, right, bottom).coerceValid()
        }
        set(value) {
            val valid = value.coerceValid()
            prefs.edit()
                .putFloat(KEY_ROI_LEFT, valid.left)
                .putFloat(KEY_ROI_TOP, valid.top)
                .putFloat(KEY_ROI_RIGHT, valid.right)
                .putFloat(KEY_ROI_BOTTOM, valid.bottom)
                .apply()
        }

    var motionSensitivity: Float
        get() = prefs.getFloat(KEY_MOTION_SENSITIVITY, 25.0f) // Limiar de variação de luminância (0 a 100)
        set(value) = prefs.edit().putFloat(KEY_MOTION_SENSITIVITY, value).apply()

    var isOledEcoMode: Boolean
        get() = prefs.getBoolean(KEY_OLED_ECO_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_OLED_ECO_MODE, value).apply()

    var isAudioAlarmEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_ALARM_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_ALARM_ENABLED, value).apply()

    var isCalibrationMode: Boolean
        get() = prefs.getBoolean(KEY_CALIBRATION_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_CALIBRATION_MODE, value).apply()

    var alarmCooldownSeconds: Int
        get() = prefs.getInt(KEY_ALARM_COOLDOWN_SECONDS, 120)
        set(value) = prefs.edit().putInt(KEY_ALARM_COOLDOWN_SECONDS, value).apply()

    var snoozeUntilTimestamp: Long
        get() = prefs.getLong(KEY_SNOOZE_UNTIL_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_SNOOZE_UNTIL_TIMESTAMP, value).apply()

    var allowBackgroundSurveillance: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_BACKGROUND_SURVEILLANCE, false)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_BACKGROUND_SURVEILLANCE, value).apply()

    fun isSnoozed(): Boolean {
        return System.currentTimeMillis() < snoozeUntilTimestamp
    }

    fun snoozeForMinutes(minutes: Int = 10) {
        snoozeUntilTimestamp = System.currentTimeMillis() + (minutes * 60 * 1000L)
    }

    fun clearSnooze() {
        snoozeUntilTimestamp = 0L
    }

    var totalDetections: Int
        get() = prefs.getInt(KEY_TOTAL_DETECTIONS, 0)
        set(value) = prefs.edit().putInt(KEY_TOTAL_DETECTIONS, value).apply()

    fun incrementDetections() {
        totalDetections += 1
    }
}
