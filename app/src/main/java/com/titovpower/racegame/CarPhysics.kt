package com.titovpower.racegame

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** How the car behaves on a ground type. Speeds in m/s, accelerations in m/s². */
data class Surface(
    val name: String,
    val accel: Float,
    val maxSpeed: Float,
    val brakeDecel: Float,
    /** Maximum yaw rate in rad/s at full steering lock. */
    val steerRate: Float,
    /** Deceleration when coasting. */
    val rollingDecel: Float,
    val maxReverse: Float,
) {
    companion object {
        val STREET = Surface("STREET", accel = 11f, maxSpeed = 58f, brakeDecel = 24f, steerRate = 1.5f, rollingDecel = 1.2f, maxReverse = 8f)
        val GRASS = Surface("GRASS", accel = 4.5f, maxSpeed = 24f, brakeDecel = 9f, steerRate = 0.75f, rollingDecel = 3.5f, maxReverse = 5f)
    }
}

/**
 * Simple arcade car model. Inputs: steer -1 (left) .. 1 (right), throttle 0..1, brake 0..1.
 * Forward direction is (sin yaw, 0, cos yaw); positive speed moves forward.
 */
class CarPhysics(var x: Float = 0f, var z: Float = 0f, var yaw: Float = 0f) {
    var speed = 0f
    /** Wheel rotation for rendering, radians. */
    var wheelSpin = 0f
    /** Smoothed front wheel angle for rendering, radians. */
    var wheelAngle = 0f
    /** Smoothed acceleration, used to pitch the body. */
    var longAccel = 0f
    /** Smoothed sideways acceleration, used to roll the body. */
    var latAccel = 0f

    private var stoppedBrakeTime = 0f

    fun update(dt: Float, steer: Float, throttle: Float, brake: Float, surface: Surface) {
        val before = speed
        val s = clamp(steer, -1f, 1f)
        val th = clamp(throttle, 0f, 1f)
        val br = clamp(brake, 0f, 1f)

        if (speed >= 0f) {
            if (th > 0f && speed < surface.maxSpeed) {
                // Torque falls off towards top speed.
                val ratio = speed / surface.maxSpeed
                speed += surface.accel * th * (1f - ratio * ratio) * dt
            }
            if (br > 0f) {
                if (speed > 0.05f) {
                    speed = maxOf(0f, speed - surface.brakeDecel * br * dt)
                    stoppedBrakeTime = 0f
                } else {
                    // Holding the brake while stopped engages reverse.
                    stoppedBrakeTime += dt
                    if (stoppedBrakeTime > 0.35f) speed -= surface.accel * 0.6f * br * dt
                }
            } else {
                stoppedBrakeTime = 0f
            }
        } else {
            // Rolling backwards: throttle brakes, brake keeps reversing.
            if (th > 0f) speed = min(0f, speed + surface.brakeDecel * th * dt)
            if (br > 0f) speed = maxOf(-surface.maxReverse, speed - surface.accel * 0.6f * br * dt)
            stoppedBrakeTime = 0f
        }

        // Coasting losses, and strong drag when carrying too much speed onto a slow surface.
        val drag = surface.rollingDecel + if (abs(speed) > surface.maxSpeed) 14f else 0f
        if (th == 0f && br == 0f || abs(speed) > surface.maxSpeed) {
            val dv = drag * dt
            speed = if (abs(speed) <= dv) 0f else speed - sign(speed) * dv
        }

        // Steering: no turning when stationary, full rate from ~10 m/s, softer at very high speed.
        val absSpeed = abs(speed)
        val lowSpeedFactor = min(1f, absSpeed / 10f)
        val highSpeedFactor = 1f / (1f + absSpeed / 70f)
        val yawRate = s * surface.steerRate * lowSpeedFactor * highSpeedFactor * sign(speed)
        // Steering right turns the nose towards -x when facing +z, i.e. decreases yaw.
        yaw -= yawRate * dt

        x += sin(yaw) * speed * dt
        z += cos(yaw) * speed * dt

        wheelSpin += speed / WHEEL_RADIUS * dt
        wheelAngle += (s * 0.45f - wheelAngle) * min(1f, dt * 12f)
        if (dt > 0f) {
            longAccel += ((speed - before) / dt - longAccel) * min(1f, dt * 4f)
            latAccel += (yawRate * speed - latAccel) * min(1f, dt * 4f)
        }
    }

    /** Hit something solid: stop and bounce back a little. */
    fun bump() {
        speed = -speed * 0.25f
    }

    companion object {
        const val WHEEL_RADIUS = 0.33f
    }
}
