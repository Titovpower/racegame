package com.titovpower.racegame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

class GameLogicTest {
    private fun speedAfter(seconds: Float, surface: Surface, spec: CarSpec = CarSpec.PORSCHE, throttle: Float = 1f): Float {
        val car = CarPhysics(spec = spec)
        var t = 0f
        while (t < seconds) {
            car.update(0.005f, 0f, throttle, 0f, surface)
            t += 0.005f
        }
        return car.speed
    }

    private fun zeroTo100(spec: CarSpec): Float {
        val car = CarPhysics(spec = spec)
        var t = 0f
        while (car.speed < 100f / 3.6f && t < 30f) {
            car.update(0.005f, 0f, 1f, 0f, Surface.STREET)
            t += 0.005f
        }
        return t
    }

    @Test
    fun everyCarMatchesItsPublishedFigures() {
        for (spec in CarSpec.ALL) {
            assertEquals("${spec.brand} 0-100", spec.zeroTo100, zeroTo100(spec), 0.15f)
            val top = speedAfter(200f, Surface.STREET, spec) * 3.6f
            assertEquals("${spec.brand} top speed", spec.topSpeedKmh, top, 6f)
        }
    }

    @Test
    fun grassAcceleratesSlowerThanStreet() {
        assertTrue(speedAfter(3f, Surface.GRASS) < speedAfter(3f, Surface.STREET) * 0.6f)
    }

    @Test
    fun grassLimitsTopSpeed() {
        assertTrue(speedAfter(60f, Surface.GRASS) * 3.6f < 110f)
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
        // 100 km/h to 0 in a realistic ~30-35 m for a sports car on asphalt.
        val car = CarPhysics()
        car.speed = 100f / 3.6f
        var d = 0f
        while (car.speed > 0f) { val z = car.z; car.update(0.005f, 0f, 0f, 1f, Surface.STREET); d += car.z - z }
        assertTrue("100-0 in $d m", d in 25f..40f)
    }

    @Test
    fun brakeStopsTheCar() {
        val car = CarPhysics()
        car.speed = 15f
        repeat(150) { car.update(0.01f, 0f, 0f, 1f, Surface.STREET) }
        assertTrue(car.speed <= 0f)
    }

    @Test
    fun holdingBrakeWhileStoppedReverses() {
        val car = CarPhysics()
        repeat(300) { car.update(0.01f, 0f, 0f, 1f, Surface.STREET) }
        assertTrue(car.speed < 0f)
        assertTrue(car.speed >= -CarPhysics.MAX_REVERSE - 0.01f)
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
        assertTrue("street=$street grass=$grass", abs(grass) < abs(street) * 0.7f)
    }

    @Test
    fun noTurningWhileStationary() {
        val car = CarPhysics()
        repeat(100) { car.update(0.01f, 1f, 0f, 0f, Surface.STREET) }
        assertEquals(0f, car.yaw, 0f)
    }

    @Test
    fun pickingACarBeforeTheRace() {
        val game = GameState(World())
        assertEquals(GameState.Phase.PICKING, game.phase)
        // Nothing moves while picking.
        val start = game.car.z
        repeat(60) { game.update(1f / 60f, 0f, 1f, 0f) }
        assertEquals(start, game.car.z, 0f)
        game.cycleCar(1)
        assertEquals(CarSpec.ALL[(CarSpec.ALL.indexOf(CarSpec.PORSCHE) + 1) % CarSpec.ALL.size], game.car.spec)
        game.cycleCar(-2)
        assertEquals(CarSpec.ALL[(CarSpec.ALL.indexOf(CarSpec.PORSCHE) - 1 + CarSpec.ALL.size) % CarSpec.ALL.size], game.playerSpec)
        game.startRace()
        assertEquals(GameState.Phase.COUNTDOWN, game.phase)
        game.cycleCar(1) // ignored once the race is on
        assertEquals(game.playerSpec, game.car.spec)
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
        val game = GameState(World(), opponents = 0)
        game.startRace()
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
            // Keep under the track's safe speed for this car.
            val safe = track.safeSpeed(car.spec)[(game.player.trackIndex + 3) % track.samples] * 0.9f
            val fast = car.speed > safe
            game.update(1f / 60f, clamp(-diff * 3f, -1f, 1f), if (fast) 0f else 0.7f, if (car.speed > safe + 2f) 0.5f else 0f)
            if (game.surface == Surface.STREET) streetFrames++
            frames++
        }
        assertTrue("lap not completed", game.lastLap > 0f)
        assertTrue("street $streetFrames of $frames", streetFrames > frames * 0.95f)
        // The grid is just past the line, so the first crossing is a full lap.
        assertEquals(1, game.player.lapsDone)
    }

    @Test
    fun driveRadiusKeepsCarOffTheHills() {
        val game = GameState(World())
        game.startRace()
        // Steer hard off the track at full throttle for a long time.
        repeat(6000) { game.update(1f / 60f, 1f, 1f, 0f) }
        assertTrue(game.world.track.nearest(game.car.x, game.car.z).distance <= game.world.driveRadius + 1f)
    }

    @Test
    fun countdownHoldsEveryoneOnTheGrid() {
        val game = GameState(World())
        game.startRace()
        val start = game.racers.map { it.car.x to it.car.z }
        repeat(120) { game.update(1f / 60f, 0f, 1f, 0f) }
        assertEquals(start, game.racers.map { it.car.x to it.car.z })
        assertEquals(9, game.racers.size)
        // The player starts at the back.
        assertEquals(9, game.position)
    }

    @Test
    fun opponentsRaceCleanlyAndAreRankedByFinish() {
        val game = GameState(World())
        game.startRace()
        val grassFrames = IntArray(game.racers.size)
        var frames = 0
        while (game.racers.any { !it.isPlayer && !it.finished } && frames < 60 * 400) {
            // Player stays parked on the grid.
            game.update(1f / 60f, 0f, 0f, 1f)
            for ((k, r) in game.racers.withIndex()) if (!r.finished && r.surface == Surface.GRASS) grassFrames[k]++
            frames++
        }
        val ai = game.racers.filter { !it.isPlayer }
        assertTrue("not all opponents finished in time", ai.all { it.finished })
        for ((k, r) in game.racers.withIndex()) {
            if (!r.isPlayer) assertTrue("${r.name} spent $frames/${grassFrames[k]} on grass", grassFrames[k] < frames / 10)
        }
        // Finishers first, in finishing order; the parked player is last.
        val finishers = game.standings.filter { it.finished }
        assertEquals(finishers.sortedBy { it.finishTime }, finishers)
        assertTrue(game.standings.last().isPlayer)
        // Five laps each, every lap a plausible time.
        for (r in ai) {
            assertTrue(r.progress >= 5f)
            assertTrue("${r.name} best lap ${r.bestLap}", r.bestLap in 20f..90f)
        }
    }

    @Test
    fun rankingFollowsDistanceCovered() {
        val game = GameState(World())
        game.startRace()
        repeat(60 * 25) { game.update(1f / 60f, 0f, 0f, 0f) }
        val progress = game.standings.map { it.progress }
        assertEquals(progress.sortedDescending(), progress)
    }

    @Test
    fun gearboxShiftsUpThroughTheGearsUnderTheRedline() {
        for (spec in CarSpec.ALL) {
            val car = CarPhysics(spec = spec)
            val box = Gearbox { spec }
            var maxRpm = 0f
            var t = 0f
            while (t < 60f) {
                car.update(0.005f, 0f, 1f, 0f, Surface.STREET)
                box.update(0.005f, car.speed, 1f)
                if (car.speed > 3f) maxRpm = maxOf(maxRpm, box.rpm)
                t += 0.005f
            }
            assertTrue("${spec.brand} top gear ${box.gear + 1}", box.gear >= spec.gears - 2)
            assertTrue("${spec.brand} max rpm $maxRpm", maxRpm <= spec.redline + 1f && maxRpm > spec.redline * 0.85f)
            // Slowing down shifts back down.
            while (car.speed > 0.5f) { car.update(0.005f, 0f, 0f, 1f, Surface.STREET); box.update(0.005f, car.speed, 0f) }
            assertEquals(0, box.gear)
        }
    }

    @Test
    fun engineSoundRisesInPitchWithRpm() {
        fun zeroCrossings(rpm: Float): Int {
            val synth = EngineSynth(22050)
            val buf = ShortArray(22050)
            // Settle, then measure a second at constant rpm.
            synth.render(buf, Engine.V8, 7000f, rpm, 0.5f, 0.8f)
            synth.render(buf, Engine.V8, 7000f, rpm, 0.5f, 0.8f)
            assertTrue(buf.maxOf { kotlin.math.abs(it.toInt()) } > 3000)
            var lowPassed = 0f
            var last = 0f
            var crossings = 0
            for (v in buf) {
                lowPassed += (v - lowPassed) * 0.05f
                if ((lowPassed > 0f) != (last > 0f)) crossings++
                last = lowPassed
            }
            return crossings
        }
        assertTrue(zeroCrossings(6000f) > zeroCrossings(1500f) * 2)
    }

    @Test
    fun glbAssetsAreWellFormed() {
        val b = MeshBuilder()
        b.box(0f, 1f, 0f, 1f, 1f, 1f, Rgb(1f, 0f, 0f))
        val glb = Glb.write(listOf(GlbPart("box", b.toArray()), GlbPart("ground", b.toArray(), Surfacing.GRASS)))
        val buf = java.nio.ByteBuffer.wrap(glb).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, buf.getInt(0)) // "glTF"
        assertEquals(glb.size, buf.getInt(8))
        val jsonLength = buf.getInt(12)
        val json = String(glb, 20, jsonLength)
        assertTrue(json.contains("\"name\":\"box\"") && json.contains("\"TEXCOORD_0\""))
        // 36 indices into far fewer shared vertices (24 corners+normals, give or take rounding).
        val vertices = Regex("\"count\":(\\d+),\"type\":\"VEC3\"").find(json)!!.groupValues[1].toInt()
        assertTrue("$vertices vertices", vertices in 24..28)
        assertTrue(json.contains("\"count\":36,\"type\":\"SCALAR\""))
        val binLength = buf.getInt(20 + jsonLength)
        assertEquals(glb.size, 20 + jsonLength + 8 + binLength)
    }

    @Test
    fun glbAssetsReadBackForTheRenderer() {
        val b = MeshBuilder()
        b.box(0f, 1f, 0f, 1f, 1f, 1f, Rgb(1f, 0f, 0f))
        val model = Gltf.parse(Glb.write(listOf(GlbPart("box", b.toArray()), GlbPart(Scene.LIGHTS_NODE, b.toArray(), Surfacing.LAMP))))
        assertEquals(2, model.primitives.size)
        val box = model.primitives[0]
        assertEquals("box", box.node)
        assertEquals(36, box.indices.size)
        // Red in linear, alpha 1; the box spans y 0..2.
        assertEquals(1f, box.colors!![0], 0.01f)
        assertEquals(0f, box.colors[1], 0.01f)
        assertEquals(2f, (0 until box.positions.size / 3).maxOf { box.positions[it * 3 + 1] }, 1e-4f)
        assertEquals("LAMP", model.materials[model.primitives[1].material].name)
        // Triangles wind counter-clockwise around their normals (the renderer culls back faces).
        for (t in 0 until 12) {
            val (i0, i1, i2) = Triple(box.indices[t * 3] * 3, box.indices[t * 3 + 1] * 3, box.indices[t * 3 + 2] * 3)
            val p = box.positions
            val e1 = V3(p[i1] - p[i0], p[i1 + 1] - p[i0 + 1], p[i1 + 2] - p[i0 + 2])
            val e2 = V3(p[i2] - p[i0], p[i2 + 1] - p[i0 + 1], p[i2 + 2] - p[i0 + 2])
            val n = V3(box.normals[i0], box.normals[i0 + 1], box.normals[i0 + 2])
            assertTrue(e1.cross(e2).dot(n) > 0f)
        }
    }

    @Test
    fun skyLightIsBrighterFromAbove() {
        val sh = SkyEnvironment.irradianceSh()
        // Irradiance up (y = 1) vs. down: band 1 index 1 is the y term.
        // the polynomial form at n = (0, +-1, 0): sh0 +- sh1 - sh6 - sh8 (red channel).
        val up = sh[0] + sh[3] - sh[18] - sh[24]
        val down = sh[0] - sh[3] - sh[18] - sh[24]
        assertTrue("up=$up down=$down", up > down && down > 0f)
    }
}
