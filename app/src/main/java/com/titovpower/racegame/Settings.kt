package com.titovpower.racegame

import android.content.Context

/** A graphics choice in the settings' OpenGL section: its label, the choices, and the default. */
enum class GraphicsOption(val label: String, val choices: List<String>, val default: Int) {
    RESOLUTION("Resolution", listOf("Auto", "100%", "75%", "50%"), 0),
    ANTI_ALIASING("Anti-aliasing", listOf("Off", "FXAA", "MSAA 4x"), 2),
    SHADOWS("Shadows", listOf("Off", "Low", "High"), 2),
    AMBIENT_OCCLUSION("Ambient occlusion", listOf("Off", "On"), 1),
    BLOOM("Bloom", listOf("Off", "On"), 1),
    GRASS("Grass & leaves", listOf("Off", "On"), 1),
    FRAME_RATE("Frame rate", listOf("30", "60", "Max"), 2),
}

/**
 * Settings behind the gear button, saved between sessions: the control sensitivities (1 = default;
 * 2 = half the finger travel for full lock, full throttle or full brake) and the OpenGL graphics options.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var steering = prefs.getFloat(STEERING, 1f)
        set(v) { field = v.coerceIn(MIN, MAX); save() }
    var throttle = prefs.getFloat(THROTTLE, 1f)
        set(v) { field = v.coerceIn(MIN, MAX); save() }
    var brake = prefs.getFloat(BRAKE, 1f)
        set(v) { field = v.coerceIn(MIN, MAX); save() }

    private val graphics = IntArray(GraphicsOption.values().size) { k ->
        val o = GraphicsOption.values()[k]
        prefs.getInt(o.key, o.default).coerceIn(0, o.choices.size - 1)
    }

    /** Goes up on every graphics change, so the renderer knows to apply them again. */
    var graphicsVersion = 0
        private set

    fun graphics(o: GraphicsOption) = graphics[o.ordinal]

    fun setGraphics(o: GraphicsOption, choice: Int) {
        if (graphics[o.ordinal] == choice) return
        graphics[o.ordinal] = choice.coerceIn(0, o.choices.size - 1)
        graphicsVersion++
        prefs.edit().putInt(o.key, graphics[o.ordinal]).apply()
    }

    private val GraphicsOption.key get() = "gl_" + name.lowercase()

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
