package com.titovpower.racegame

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One car in the race: the player or an AI opponent. */
class Racer(
    val name: String,
    /** Body paint, 0xRRGGBB. */
    val color: Int,
    val isPlayer: Boolean,
    /** AI only: fraction of the track's safe speed this driver dares to carry, ~0.8..1. */
    val skill: Float,
    /** AI only: preferred distance left (+) or right (-) of the centerline, meters. */
    val lane: Float,
    val car: CarPhysics,
    startIndex: Int,
) {
    val spec get() = car.spec

    var surface = Surface.STREET
        internal set
    var braking = false
        internal set

    /** Laps driven as a continuous number: negative before the start line, 3.0 = three laps done. */
    var progress = 0f
        internal set
    var trackIndex = startIndex
        internal set

    var lapTime = 0f
        internal set
    var lastLap = 0f
        internal set
    var bestLap = 0f
        internal set
    /** Total race time when this racer finished, or 0 while still racing. */
    var finishTime = 0f
        internal set
    val finished get() = finishTime > 0f

    /** Completed laps (0 until the first full lap is done). */
    val lapsDone get() = maxOf(0, progress.toInt())
}

/** A standings row, as shown in the HUD. */
class Standing(val name: String, val car: String, val color: Int, val isPlayer: Boolean, val finished: Boolean)

/**
 * Race simulation: the player plus AI opponents on one track, independent of rendering.
 * Starts with a countdown, runs [totalLaps] laps, and ranks racers by how far they have got.
 */
class GameState(val world: World, val opponents: Int = 8, val totalLaps: Int = 5) {
    enum class Phase { PICKING, COUNTDOWN, RACING }

    var phase = Phase.PICKING
        private set
    /** The car the player drives, chosen before the race. */
    var playerSpec = CarSpec.PORSCHE
        private set
    val racers = ArrayList<Racer>()
    /** The player's automatic gearbox (drives the engine sound and rev counter). */
    val gearbox = Gearbox { playerSpec }
    lateinit var player: Racer
        private set

    /** Seconds left in the start countdown (cars are held while > 0). */
    var countdown = 0f
        private set
    var raceTime = 0f
        private set

    /** Racers sorted by position: finishers by finish time, then by distance covered. */
    var standings: List<Racer> = emptyList()
        private set

    val car get() = player.car
    val surface get() = player.surface
    val lapTime get() = player.lapTime
    val lastLap get() = player.lastLap
    val bestLap get() = player.bestLap
    val position get() = standings.indexOf(player) + 1

    init {
        reset()
    }

    /** Puts everyone back on the grid; the race starts with [startRace]. */
    fun reset() {
        racers.clear()
        val track = world.track
        val n = track.samples
        // Two-column grid just past the finish line, so the next five crossings are the five laps.
        // The player starts at the back.
        val count = opponents + 1
        val rows = (count + 1) / 2
        for (slot in 0 until count) {
            val row = slot / 2
            val idx = (4 + (rows - 1 - row) * 6) % n
            val t = track.tangent(idx)
            val side = if (slot % 2 == 0) 2.8f else -2.8f
            val x = track.xs[idx] + t.z * side
            val z = track.zs[idx] - t.x * side
            val racer = if (slot == count - 1) {
                Racer("YOU", PLAYER_COLOR, true, 1f, 0f, CarPhysics(x, z, track.headingAt(idx), playerSpec), idx)
            } else {
                val d = DRIVERS[slot % DRIVERS.size]
                Racer(d.name, d.color, false, d.skill, d.lane, CarPhysics(x, z, track.headingAt(idx), d.spec), idx)
            }
            racer.progress = idx.toFloat() / n
            racers.add(racer)
        }
        player = racers.last()
        phase = Phase.PICKING
        gearbox.reset()
        countdown = COUNTDOWN
        raceTime = 0f
        updateStandings()
    }

    /** Before the race: switch the player's car to the next (+1) or previous (-1) model. */
    fun cycleCar(delta: Int) {
        if (phase != Phase.PICKING) return
        val all = CarSpec.ALL
        playerSpec = all[(all.indexOf(playerSpec) + delta + all.size) % all.size]
        player.car.spec = playerSpec
    }

    fun startRace() {
        if (phase == Phase.PICKING) phase = Phase.COUNTDOWN
    }

    fun update(dt: Float, steer: Float, throttle: Float, brake: Float) {
        // Revving on the grid works before the start, too.
        gearbox.update(dt, car.speed, throttle)
        if (phase == Phase.PICKING) return
        if (phase == Phase.COUNTDOWN) {
            countdown -= dt
            // Engines rev, but nobody moves until the lights go out.
            for (r in racers) r.braking = true
            if (countdown > 0f) return
            countdown = 0f
            phase = Phase.RACING
        }
        raceTime += dt
        for (r in racers) {
            if (r.isPlayer) {
                drive(r, dt, steer, throttle, brake)
            } else {
                val (s, th, br) = aiInputs(r)
                drive(r, dt, s, th, br)
            }
        }
        resolveCarCollisions()
        updateStandings()
    }

    private fun drive(r: Racer, dt: Float, steer: Float, throttle: Float, brake: Float) {
        val track = world.track
        val car = r.car
        val near = track.nearestLocal(car.x, car.z, r.trackIndex)
        r.surface = if (near.distance <= track.halfWidth + track.curbWidth) Surface.STREET else Surface.GRASS
        r.braking = brake > 0.05f

        val oldX = car.x
        val oldZ = car.z
        car.update(dt, steer, throttle, brake, r.surface)

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
        var after = track.nearestLocal(car.x, car.z, near.index)
        if (after.distance > world.driveRadius) {
            car.x = oldX
            car.z = oldZ
            car.bump()
            after = near
        }
        updateProgress(r, dt, after.index)
    }

    private fun updateProgress(r: Racer, dt: Float, index: Int) {
        val n = world.track.samples
        var delta = index - r.trackIndex
        if (delta > n / 2) delta -= n
        if (delta < -n / 2) delta += n
        r.trackIndex = index
        if (r.finished) return

        val before = r.progress
        r.progress += delta.toFloat() / n
        r.lapTime += dt
        // Crossing the finish line forwards completes a lap.
        if (kotlin.math.floor(r.progress) > kotlin.math.floor(before) && r.progress >= 1f) {
            r.lastLap = r.lapTime
            if (r.bestLap == 0f || r.lapTime < r.bestLap) r.bestLap = r.lapTime
            r.lapTime = 0f
            if (r.progress >= totalLaps) r.finishTime = raceTime
        }
    }

    /** Simple AI: follow its lane along the track, keep to the safe speed, and avoid the car ahead. */
    private fun aiInputs(r: Racer): Triple<Float, Float, Float> {
        val track = world.track
        val car = r.car
        val n = track.samples
        val speed = car.speed
        val ahead = (r.trackIndex + 8 + (speed * 0.25f).toInt()) % n

        // Swing out of the lane to pass a slower car just ahead.
        var lane = r.lane
        val fx = sin(car.yaw)
        val fz = cos(car.yaw)
        for (o in racers) {
            if (o === r) continue
            val dx = o.car.x - car.x
            val dz = o.car.z - car.z
            val along = dx * fx + dz * fz
            val side = dx * fz - dz * fx // + = other car is to our left
            if (along in 0f..14f && abs(side) < 2.6f && o.car.speed < speed + 1f) {
                lane = if (side > 0f) lane - 3.2f else lane + 3.2f
                break
            }
        }
        lane = clamp(lane, -track.halfWidth + 1.6f, track.halfWidth - 1.6f)

        val t = track.tangent(ahead)
        val tx = track.xs[ahead] + t.z * lane
        val tz = track.zs[ahead] - t.x * lane
        val desired = atan2(tx - car.x, tz - car.z)
        var diff = desired - car.yaw
        while (diff > PI) diff -= (2 * PI).toFloat()
        while (diff < -PI) diff += (2 * PI).toFloat()
        val steer = clamp(-diff * 2.6f, -1f, 1f)

        // Look a little ahead on the speed profile; slower when off the road.
        var target = track.safeSpeed(r.spec)[(r.trackIndex + 3) % n] * r.skill
        if (r.surface == Surface.GRASS) target = minOf(target, 22f)
        return when {
            speed < target - 0.5f -> Triple(steer, clamp((target - speed) / 4f, 0.3f, 1f), 0f)
            speed > target + 1.5f -> Triple(steer, 0f, clamp((speed - target) / 6f, 0.2f, 1f))
            // Hold speed: just enough throttle to beat drag.
            else -> Triple(steer, clamp(r.spec.resistance(speed, r.surface) * r.spec.massKg * maxOf(speed, 3f) / r.spec.wheelPower, 0.05f, 1f), 0f)
        }
    }

    /** Cars are two circles each (front and rear); overlapping cars are pushed apart. */
    private fun resolveCarCollisions() {
        val r = 1.05f
        val off = 1.15f
        for (i in racers.indices) for (j in i + 1 until racers.size) {
            val a = racers[i].car
            val b = racers[j].car
            val ddx = a.x - b.x
            val ddz = a.z - b.z
            if (ddx * ddx + ddz * ddz > 36f) continue
            for (sa in floatArrayOf(-off, off)) for (sb in floatArrayOf(-off, off)) {
                val ax = a.x + sin(a.yaw) * sa
                val az = a.z + cos(a.yaw) * sa
                val bx = b.x + sin(b.yaw) * sb
                val bz = b.z + cos(b.yaw) * sb
                val dx = ax - bx
                val dz = az - bz
                val d2 = dx * dx + dz * dz
                if (d2 < 4f * r * r) {
                    val d = sqrt(d2).coerceAtLeast(0.001f)
                    val push = (2f * r - d) * 0.5f
                    a.x += dx / d * push; a.z += dz / d * push
                    b.x -= dx / d * push; b.z -= dz / d * push
                    // The car behind loses some speed, the one in front gets a nudge.
                    val aBehind = (bx - ax) * sin(a.yaw) + (bz - az) * cos(a.yaw) > 0f
                    val rear = if (aBehind) a else b
                    val front = if (aBehind) b else a
                    val diff = rear.speed - front.speed
                    if (diff > 0f) {
                        rear.speed -= diff * 0.5f
                        front.speed += diff * 0.3f
                    }
                }
            }
        }
    }

    private fun updateStandings() {
        standings = racers.sortedWith(
            compareBy<Racer>({ if (it.finished) 0 else 1 }, { if (it.finished) it.finishTime else -it.progress }),
        )
    }

    fun standingsSnapshot(): List<Standing> = standings.map { Standing(it.name, it.spec.brand, it.color, it.isPlayer, it.finished) }

    private class Driver(val name: String, val spec: CarSpec, val color: Int, val skill: Float, val lane: Float)

    companion object {
        const val CAR_RADIUS = 1.1f
        const val COUNTDOWN = 3.5f
        const val PLAYER_COLOR = 0xC8102E

        private val DRIVERS = listOf(
            Driver("Rossi", CarSpec.LAMBORGHINI, 0x6DAA2C, 0.97f, 1.2f),
            Driver("Becker", CarSpec.PORSCHE, 0xB9BEC4, 0.96f, -1.5f),
            Driver("Silva", CarSpec.AUDI, 0x1F4E9A, 0.95f, 2.0f),
            Driver("Novak", CarSpec.MERCEDES, 0x4C6B3C, 0.93f, -0.6f),
            Driver("Tanaka", CarSpec.ASTON, 0x0E4D3B, 0.92f, 0.8f),
            Driver("Dubois", CarSpec.BMW, 0xE3B92C, 0.90f, -2.2f),
            Driver("Moreau", CarSpec.LAMBORGHINI, 0xF28C00, 0.88f, 1.6f),
            Driver("Kowalski", CarSpec.BMW, 0xEDEDED, 0.86f, -1.0f),
        )
    }
}
