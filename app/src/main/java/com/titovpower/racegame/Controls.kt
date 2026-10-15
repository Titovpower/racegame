package com.titovpower.racegame

/** Touch input written by the overlay, read by the game loop. */
class Controls {
    @Volatile var steer = 0f
    @Volatile var throttle = 0f
    @Volatile var brake = 0f
    /** Set by the UI to start a new race; cleared by the game loop. */
    @Volatile var restart = false
    /** Car picker: models to step forward (+) or back (-), consumed by the game loop. */
    val pick = java.util.concurrent.atomic.AtomicInteger(0)
    /** Set by the UI when START is pressed in the car picker. */
    @Volatile var start = false
}

/** Values the HUD and the engine sound read, written by the game loop. */
class Telemetry {
    @Volatile var speedKmh = 0f
    @Volatile var surfaceName = "STREET"
    @Volatile var lapTime = 0f
    @Volatile var lastLap = 0f
    @Volatile var bestLap = 0f
    @Volatile var standings: List<Standing> = emptyList()
    @Volatile var position = 0
    @Volatile var lap = 0
    @Volatile var totalLaps = 0
    @Volatile var countdown = 0f
    @Volatile var raceTime = 0f
    @Volatile var finished = false
    /** Race time at which the player finished. */
    @Volatile var finishTime = 0f
    /** True while the player is choosing a car before the race. */
    @Volatile var picking = false
    @Volatile var carSpec: CarSpec = CarSpec.PORSCHE
    @Volatile var rpm = CarSpec.IDLE_RPM
    /** 1-based gear, 0 = reverse. */
    @Volatile var gear = 1
    @Volatile var shifting = false
    @Volatile var throttle = 0f
    /** Screen position (fractions of width/height) just above the player's car, or negative if off screen. */
    @Volatile var carScreenX = -1f
    @Volatile var carScreenY = -1f
    /** True while the world is being generated and loaded. */
    @Volatile var loading = false
    /** Set when the world could not be built or loaded; shown on screen. */
    @Volatile var error: String? = null
}
