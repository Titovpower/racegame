package com.titovpower.racegame

import android.content.Context

/**
 * Control sensitivities set with the sliders behind the gear button, saved between sessions.
 * 1 = default; 2 = half the finger travel for full lock, full throttle or full brake.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var steering = prefs.getFloat(STEERING, 1f)
        set(v) { field = v.coerceIn(MIN, MAX); save() }
    var throttle = prefs.getFloat(THROTTLE, 1f)
        set(v) { field = v.coerceIn(MIN, MAX); save() }
    var brake = prefs.getFloat(BRAKE, 1f)
        set(v) { field = v.coerceIn(MIN, MAX); save() }

    private fun save() {
        prefs.edit().putFloat(STEERING, steering).putFloat(THROTTLE, throttle).putFloat(BRAKE, brake).apply()
    }

    companion object {
        const val MIN = 0.4f
        const val MAX = 2.5f
        private const val STEERING = "steering"
        private const val THROTTLE = "throttle"
        private const val BRAKE = "brake"
    }
}
