package com.titovpower.racegame

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

const val GRAVITY = 9.81f
private const val AIR_DENSITY = 1.2f

/** Ground type under the tires. */
data class Surface(
    val name: String,
    /** Tire grip relative to dry asphalt. */
    val grip: Float,
    /** Rolling resistance coefficient. */
    val rolling: Float,
    /** Extra drag from bumps and soft ground, as deceleration per m/s of speed. */
    val rough: Float,
    /** Share of engine power that becomes thrust (the rest is lost to wheelspin). */
    val power: Float = 1f,
) {
    companion object {
        val STREET = Surface("STREET", grip = 1f, rolling = 0.013f, rough = 0f)
        val GRASS = Surface("GRASS", grip = 0.45f, rolling = 0.06f, rough = 0.05f, power = 0.25f)
    }
}

/**
 * Real-world figures for one car (published manufacturer data, rounded). Drag area and launch
 * traction are not published, so they are solved from the top speed and 0-100 km/h time.
 */
class CarSpec(
    val brand: String,
    val model: String,
    val massKg: Float,
    val powerKw: Float,
    val topSpeedKmh: Float,
    val zeroTo100: Float,
    val awd: Boolean,
    val wheelbase: Float,
    /** Peak lateral grip in g on dry asphalt (tires and chassis). */
    val gripG: Float,
    /** Extra grip in g from downforce at 60 m/s (216 km/h). */
    val aeroGripG: Float,
    /** Peak braking deceleration in g on dry asphalt. */
    val brakeG: Float,
    val wheelRadius: Float = 0.345f,
    /** Maximum front wheel angle at standstill, radians. */
    val maxSteer: Float = 0.6f,
    val engine: Engine = Engine.V8,
    val redline: Float = 7000f,
    val gears: Int = 7,
) {
    /** Power reaching the wheels after drivetrain losses, W. */
    val wheelPower = powerKw * 1000f * 0.88f
    val topSpeed = topSpeedKmh / 3.6f

    /** Drag coefficient times frontal area (m²), so that wheel power balances drag at top speed. */
    val dragArea: Float = run {
        val v = topSpeed
        val rollingForce = Surface.STREET.rolling * massKg * GRAVITY
        ((wheelPower / v - rollingForce) / (0.5f * AIR_DENSITY * v * v)).coerceAtLeast(0.2f)
    }

    /** Fraction of weight usable for forward traction; solved so 0-100 km/h matches [zeroTo100]. */
    val traction: Float = run {
        var lo = 0.3f
        var hi = 2.5f
        repeat(30) {
            val mid = (lo + hi) / 2f
            if (zeroTo100Time(mid) > zeroTo100) lo = mid else hi = mid
        }
        (lo + hi) / 2f
    }

    val powerHp get() = (powerKw * 1.36f).toInt()

    /**
     * Overall gear ratios (gearbox x final drive), geometric from 1st to top gear: top gear reaches
     * top speed just under the redline, 1st gear runs out at ~28% of top speed.
     */
    val gearRatios: FloatArray = run {
        val top = redline * 0.97f * RPM_TO_RAD / (topSpeed / wheelRadius)
        val first = redline * RPM_TO_RAD / (topSpeed * 0.28f / wheelRadius)
        FloatArray(gears) { g -> first * Math.pow((top / first).toDouble(), g / (gears - 1.0)).toFloat() }
    }

    /** Engine speed in gear [gear] (0-based) at road speed v, never below idle. */
    fun rpm(v: Float, gear: Int) = maxOf(IDLE_RPM, abs(v) / wheelRadius * gearRatios[gear] / RPM_TO_RAD)

    private fun zeroTo100Time(tractionGuess: Float): Float {
        var v = 0f
        var t = 0f
        val dt = 0.005f
        while (v < 100f / 3.6f && t < 30f) {
            v += driveAccel(v, 1f, Surface.STREET, tractionGuess) * dt
            t += dt
        }
        return t
    }

    /** Forward acceleration from engine and resistances at speed v (m/s), throttle 0..1. */
    fun driveAccel(v: Float, throttle: Float, surface: Surface, tractionFactor: Float = traction): Float {
        val powerLimited = throttle * surface.power * wheelPower / (massKg * maxOf(v, 3f))
        val tractionLimited = throttle * tractionFactor * surface.grip * GRAVITY
        return min(powerLimited, tractionLimited) - resistance(v, surface)
    }

    /** Deceleration from air drag, rolling resistance and rough ground at speed v >= 0. */
    fun resistance(v: Float, surface: Surface) =
        0.5f * AIR_DENSITY * dragArea * v * v / massKg + surface.rolling * GRAVITY + surface.rough * v

    fun lateralLimit(v: Float, surface: Surface) = (gripG + aeroGripG * (v / 60f) * (v / 60f)) * surface.grip * GRAVITY

    /** Front wheel angle at full steering input; reduced at speed so a touch wheel stays controllable. */
    fun steerAngle(v: Float) = maxSteer / (1f + abs(v) / 14f)

    /** Fastest speed at which a curve of curvature k (1/m) can be taken on asphalt. */
    fun cornerSpeed(k: Float): Float {
        if (k < 1e-5f) return topSpeed
        var v = topSpeed
        while (v > 5f) {
            val needed = v * k
            val kinematic = v * tan(steerAngle(v)) / wheelbase
            val grip = lateralLimit(v, Surface.STREET) / v
            if (needed <= min(kinematic, grip)) return v
            v -= 0.5f
        }
        return 5f
    }

    companion object {
        val LAMBORGHINI = CarSpec("Lamborghini", "Huracán EVO", 1550f, 470f, 325f, 2.9f, true, 2.62f, 1.30f, 0.20f, 1.30f, engine = Engine.V10, redline = 8500f, gears = 7)
        val PORSCHE = CarSpec("Porsche", "911 GT3 RS", 1450f, 386f, 296f, 3.2f, false, 2.46f, 1.38f, 0.40f, 1.38f, engine = Engine.FLAT6, redline = 9000f, gears = 7)
        val BMW = CarSpec("BMW", "M4 Competition", 1775f, 390f, 290f, 3.5f, true, 2.86f, 1.12f, 0.05f, 1.20f, engine = Engine.INLINE6, redline = 7200f, gears = 8)
        val MERCEDES = CarSpec("Mercedes-AMG", "ONE", 1695f, 782f, 352f, 2.9f, true, 2.72f, 1.40f, 0.40f, 1.40f, engine = Engine.V6, redline = 11000f, gears = 7)
        val AUDI = CarSpec("Audi", "R8 V10 performance", 1595f, 456f, 331f, 3.1f, true, 2.65f, 1.25f, 0.15f, 1.28f, engine = Engine.V10, redline = 8700f, gears = 7)
        val ASTON = CarSpec("Aston Martin", "Vantage", 1605f, 489f, 325f, 3.5f, false, 2.70f, 1.20f, 0.12f, 1.25f, engine = Engine.V8, redline = 7000f, gears = 8)

        val ALL = listOf(LAMBORGHINI, PORSCHE, BMW, MERCEDES, AUDI, ASTON)

        const val IDLE_RPM = 950f
        /** rev/min to rad/s. */
        const val RPM_TO_RAD = (2 * Math.PI / 60).toFloat()
    }
}

/** Engine layout: sets the sound (firing order and character). */
enum class Engine(val cylinders: Int, val label: String) {
    V10(10, "V10"), FLAT6(6, "flat-six"), INLINE6(6, "inline-six"), V8(8, "V8"), V6(6, "V6 hybrid");
}

/**
 * Automatic gearbox: shifts up near the redline, down when revs drop, with a short
 * shift time. Used for the engine sound and the rev counter.
 */
class Gearbox(private val spec: () -> CarSpec) {
    /** 0-based gear; -1 is reverse. */
    var gear = 0
        private set
    var rpm = CarSpec.IDLE_RPM
        private set
    /** > 0 while a shift is in progress (seconds left). */
    var shifting = 0f
        private set

    fun reset() {
        gear = 0
        rpm = CarSpec.IDLE_RPM
        shifting = 0f
    }

    fun update(dt: Float, speed: Float, throttle: Float) {
        val s = spec()
        if (speed < -0.3f) {
            gear = -1
            rpm = s.rpm(speed, 0)
            return
        }
        if (gear < 0) gear = 0
        shifting = maxOf(0f, shifting - dt)
        val wheelRpm = s.rpm(speed, gear)
        if (shifting == 0f) {
            if (wheelRpm > s.redline * 0.96f && gear < s.gears - 1) {
                gear++
                shifting = 0.12f
            } else if (gear > 0 && s.rpm(speed, gear - 1) < s.redline * (if (throttle > 0.5f) 0.7f else 0.5f)) {
                gear--
                shifting = 0.12f
            }
        }
        // Stationary or slipping clutch: throttle revs the engine freely.
        val free = CarSpec.IDLE_RPM + throttle * (s.redline * 0.55f - CarSpec.IDLE_RPM)
        val target = if (gear == 0 && wheelRpm < free) maxOf(wheelRpm, free * clamp(1f - speed / 8f, 0f, 1f)) else wheelRpm
        rpm += (target.coerceAtMost(s.redline) - rpm) * minOf(1f, dt * 14f)
    }
}

/**
 * Car dynamics: longitudinal forces from power, traction, drag and brakes; turning from the
 * steering geometry, limited by tire grip. Inputs: steer -1 (left) .. 1 (right), throttle 0..1, brake 0..1.
 * Forward direction is (sin yaw, 0, cos yaw); positive speed moves forward.
 */
class CarPhysics(var x: Float = 0f, var z: Float = 0f, var yaw: Float = 0f, var spec: CarSpec = CarSpec.PORSCHE) {
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
        val v = abs(speed)
        val brakeDecel = spec.brakeG * surface.grip * GRAVITY

        if (speed >= 0f) {
            var a = spec.driveAccel(speed, th, surface)
            if (br > 0f) {
                if (speed > 0.05f) {
                    a -= brakeDecel * br
                    stoppedBrakeTime = 0f
                } else {
                    // Holding the brake while stopped engages reverse.
                    stoppedBrakeTime += dt
                    if (stoppedBrakeTime > 0.35f) a = -REVERSE_ACCEL * surface.grip * br
                }
            } else {
                stoppedBrakeTime = 0f
            }
            speed += a * dt
            // Resistances and brakes stop the car; they never push it backwards.
            if (speed < 0f && before >= 0f && stoppedBrakeTime <= 0.35f) speed = 0f
        } else {
            // Rolling backwards: throttle brakes, brake keeps reversing.
            var a = spec.resistance(v, surface)
            if (th > 0f) a += brakeDecel * th
            if (br > 0f) a -= REVERSE_ACCEL * surface.grip * br
            speed = minOf(0f, speed + a * dt).coerceAtLeast(-MAX_REVERSE * if (surface.grip < 1f) 0.6f else 1f)
            if (th > 0f && speed == 0f) stoppedBrakeTime = 0f
        }

        // Turning: the steering geometry asks for v·tan(δ)/L, the tires allow at most a_lat/v.
        val absSpeed = abs(speed)
        val kinematic = absSpeed * tan(spec.steerAngle(absSpeed) * abs(s)) / spec.wheelbase
        val gripLimit = if (absSpeed < 0.5f) kinematic else spec.lateralLimit(absSpeed, surface) / absSpeed
        val yawRate = min(kinematic, gripLimit) * sign(s) * sign(speed)
        // Steering right turns the nose towards -x when facing +z, i.e. decreases yaw.
        yaw -= yawRate * dt
        // Running at the grip limit scrubs speed (tires sliding).
        if (kinematic > gripLimit && absSpeed > 1f) {
            val scrub = (1f - gripLimit / kinematic) * 3f * surface.grip
            speed -= sign(speed) * min(absSpeed, scrub * dt)
        }

        x += sin(yaw) * speed * dt
        z += cos(yaw) * speed * dt

        wheelSpin += speed / spec.wheelRadius * dt
        wheelAngle += (s * spec.steerAngle(absSpeed) - wheelAngle) * min(1f, dt * 12f)
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
        const val REVERSE_ACCEL = 3.5f
        const val MAX_REVERSE = 8f
    }
}
