package com.titovpower.racegame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

class GameLogicTest {
    private fun speedAfter(seconds: Float, surface: Surface, throttle: Float = 1f): Float {
        val car = CarPhysics()
        var t = 0f
        while (t < seconds) {
            car.update(0.01f, 0f, throttle, 0f, surface)
            t += 0.01f
        }
        return car.speed
    }

    @Test
    fun grassAcceleratesSlowerThanStreet() {
        assertTrue(speedAfter(3f, Surface.GRASS) < speedAfter(3f, Surface.STREET) * 0.6f)
    }

    @Test
    fun topSpeedIsLimitedBySurface() {
        assertTrue(speedAfter(60f, Surface.STREET) <= Surface.STREET.maxSpeed + 0.01f)
        assertTrue(speedAfter(60f, Surface.GRASS) <= Surface.GRASS.maxSpeed + 0.01f)
    }

    private fun brakingDistance(surface: Surface): Float {
        val car = CarPhysics()
        car.speed = 20f
        val start = car.z
        repeat(2000) {
            if (car.speed > 0f) car.update(0.01f, 0f, 0f, 1f, surface)
        }
        return car.z - start
    }

    @Test
    fun brakingIsWeakerOnGrass() {
        val street = brakingDistance(Surface.STREET)
        val grass = brakingDistance(Surface.GRASS)
        assertTrue("street=$street grass=$grass", grass > street * 1.5f)
    }

    @Test
    fun brakeStopsTheCar() {
        val car = CarPhysics()
        car.speed = 15f
        repeat(80) { car.update(0.01f, 0f, 0f, 1f, Surface.STREET) }
        assertEquals(0f, car.speed, 0.001f)
    }

    @Test
    fun holdingBrakeWhileStoppedReverses() {
        val car = CarPhysics()
        repeat(200) { car.update(0.01f, 0f, 0f, 1f, Surface.STREET) }
        assertTrue(car.speed < 0f)
        assertTrue(car.speed >= -Surface.STREET.maxReverse - 0.01f)
    }

    private fun yawChange(surface: Surface): Float {
        val car = CarPhysics()
        car.speed = 15f
        repeat(100) { car.update(0.01f, 1f, 0.3f, 0f, surface) }
        return car.yaw
    }

    @Test
    fun steeringRightTurnsRightAndIsSlowerOnGrass() {
        val street = yawChange(Surface.STREET)
        val grass = yawChange(Surface.GRASS)
        // Facing +z, right is -x: yaw decreases.
        assertTrue(street < 0f)
        assertTrue(abs(grass) < abs(street) * 0.7f)
    }

    @Test
    fun noTurningWhileStationary() {
        val car = CarPhysics()
        repeat(100) { car.update(0.01f, 1f, 0f, 0f, Surface.STREET) }
        assertEquals(0f, car.yaw, 0f)
    }

    @Test
    fun trackCenterIsRoadAndFarAwayIsGrass() {
        val track = Track()
        assertTrue(track.isOnRoad(track.xs[10], track.zs[10]))
        assertFalse(track.isOnRoad(0f, 0f))
        assertTrue(track.length() > 1000f)
    }

    @Test
    fun treesStayOffTheRoad() {
        val world = World()
        assertTrue(world.trees.size > 300)
        for (t in world.trees) {
            assertTrue(world.track.nearest(t.x, t.z).distance > world.track.halfWidth + world.track.curbWidth + 4f)
        }
    }

    @Test
    fun autopilotCompletesALapOnTheStreet() {
        val game = GameState(World())
        val track = game.world.track
        var streetFrames = 0
        var frames = 0
        while (game.lastLap == 0f && frames < 60 * 300) {
            val car = game.car
            val target = (track.nearest(car.x, car.z).index + 12) % track.samples
            val desired = atan2(track.xs[target] - car.x, track.zs[target] - car.z)
            var diff = desired - car.yaw
            while (diff > PI) diff -= (2 * PI).toFloat()
            while (diff < -PI) diff += (2 * PI).toFloat()
            // Steering right lowers yaw, so steer against the yaw error.
            game.update(1f / 60f, clamp(-diff * 3f, -1f, 1f), 0.5f, 0f)
            if (game.surface == Surface.STREET) streetFrames++
            frames++
        }
        assertTrue("lap not completed", game.lastLap > 0f)
        assertTrue(streetFrames > frames * 0.95f)
    }

    @Test
    fun driveRadiusKeepsCarOffTheHills() {
        val game = GameState(World())
        // Steer hard off the track at full throttle for a long time.
        repeat(6000) { game.update(1f / 60f, 1f, 1f, 0f) }
        assertTrue(game.world.track.nearest(game.car.x, game.car.z).distance <= game.world.driveRadius + 1f)
    }
}
