package com.titovpower.racegame

import kotlin.math.sqrt

/** Car + world simulation, independent of rendering. */
class GameState(val world: World) {
    val car: CarPhysics
    var surface = Surface.STREET
        private set

    /** Current lap time in seconds, or 0 before the first start-line crossing. */
    var lapTime = 0f
        private set
    var lastLap = 0f
        private set
    var bestLap = 0f
        private set

    private var lapRunning = false
    private var passedHalfway = false
    private var lastIndex: Int

    init {
        val track = world.track
        // Start a little before the start/finish line, facing along the track.
        val startIdx = track.samples - 8
        car = CarPhysics(track.xs[startIdx], track.zs[startIdx], track.headingAt(startIdx))
        lastIndex = startIdx
    }

    fun update(dt: Float, steer: Float, throttle: Float, brake: Float) {
        val track = world.track
        val near = track.nearest(car.x, car.z)
        surface = if (near.distance <= track.halfWidth + track.curbWidth) Surface.STREET else Surface.GRASS

        val oldX = car.x
        val oldZ = car.z
        car.update(dt, steer, throttle, brake, surface)

        // Trees and the grandstand are solid.
        for (t in world.obstacles) {
            val dx = car.x - t.x
            val dz = car.z - t.z
            val minDist = t.radius + CAR_RADIUS
            val d2 = dx * dx + dz * dz
            if (d2 < minDist * minDist) {
                val d = sqrt(d2).coerceAtLeast(0.001f)
                car.x = t.x + dx / d * minDist
                car.z = t.z + dz / d * minDist
                car.bump()
            }
        }

        // Invisible fence where the hills start.
        val after = track.nearest(car.x, car.z)
        if (after.distance > world.driveRadius) {
            car.x = oldX
            car.z = oldZ
            car.bump()
        }

        updateLap(dt, after.index)
    }

    private fun updateLap(dt: Float, index: Int) {
        val n = world.track.samples
        if (lapRunning) lapTime += dt
        if (index in (n * 2 / 5)..(n * 3 / 5)) passedHalfway = true

        // Crossing sample 0 going forward = crossing the start/finish line.
        val crossed = lastIndex > n * 9 / 10 && index < n / 10
        if (crossed) {
            if (lapRunning && passedHalfway) {
                lastLap = lapTime
                if (bestLap == 0f || lapTime < bestLap) bestLap = lapTime
            }
            if (!lapRunning || passedHalfway) {
                lapRunning = true
                lapTime = 0f
                passedHalfway = false
            }
        }
        lastIndex = index
    }

    companion object {
        const val CAR_RADIUS = 1.1f
    }
}
