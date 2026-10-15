package com.titovpower.racegame

import kotlin.math.abs

/**
 * Advances the race from the touch [controls] and publishes what the HUD and the engine
 * sound need to [telemetry]. Independent of the rendering engine.
 */
class GameLoop(val game: GameState, val scene: Scene, private val controls: Controls, private val telemetry: Telemetry) {
    fun step(dt: Float, aspect: Float) {
        if (controls.restart) {
            controls.restart = false
            game.reset()
        }
        val pick = controls.pick.getAndSet(0)
        if (pick != 0) game.cycleCar(pick)
        game.safeDriving = controls.safeDriving
        if (controls.start) {
            controls.start = false
            game.startRace()
        }
        // Simulate in small fixed steps for stable physics.
        var remaining = dt.coerceAtMost(0.05f)
        while (remaining > 0f) {
            val step = minOf(remaining, 1f / 120f)
            game.update(step, controls.steer, controls.throttle, controls.brake)
            remaining -= step
        }
        val car = game.car
        telemetry.speedKmh = abs(car.speed) * 3.6f
        telemetry.surfaceName = game.surface.name
        telemetry.lapTime = game.lapTime
        telemetry.lastLap = game.lastLap
        telemetry.bestLap = game.bestLap
        telemetry.standings = game.standingsSnapshot()
        telemetry.position = game.position
        telemetry.lap = minOf(game.player.lapsDone + 1, game.totalLaps)
        telemetry.totalLaps = game.totalLaps
        telemetry.countdown = game.countdown
        telemetry.raceTime = game.raceTime
        telemetry.finished = game.player.finished
        telemetry.finishTime = game.player.finishTime
        telemetry.picking = game.phase == GameState.Phase.PICKING
        telemetry.carSpec = game.playerSpec
        telemetry.rpm = game.gearbox.rpm
        telemetry.gear = game.gearbox.gear + 1
        telemetry.shifting = game.gearbox.shifting > 0f
        telemetry.throttle = controls.throttle

        scene.updateCamera(dt, aspect)
        val above = scene.playerScreenPoint(2.3f)
        telemetry.carScreenX = above?.first ?: -1f
        telemetry.carScreenY = above?.second ?: -1f
    }
}
