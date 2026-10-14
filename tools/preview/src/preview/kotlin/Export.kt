import com.titovpower.racegame.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

// Runs the game headlessly, then exports the scene's meshes and one frame's draw calls for the WebGL preview.
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    val seconds = (System.getenv("PREVIEW_T") ?: "6").toFloat()
    val offroad = System.getenv("PREVIEW_GRASS") != null
    val brake = System.getenv("PREVIEW_BRAKE") != null
    val aspect = (System.getenv("PREVIEW_ASPECT") ?: "2.0").toFloat()
    val game = GameState(World())
    System.getenv("PREVIEW_CAR")?.let { name -> while (!game.playerSpec.brand.startsWith(name, true)) game.cycleCar(1) }
    if (seconds > 0f) game.startRace()
    val track = game.world.track
    var t = 0f
    while (t < seconds + (if (seconds > 0f) GameState.COUNTDOWN else 1f)) {
        val car = game.car
        val target = (track.nearest(car.x, car.z).index + 12) % track.samples
        val desired = atan2(track.xs[target] - car.x, track.zs[target] - car.z)
        var diff = desired - car.yaw
        while (diff > PI) diff -= (2 * PI).toFloat()
        while (diff < -PI) diff += (2 * PI).toFloat()
        val steer = if (offroad && t > seconds + GameState.COUNTDOWN - 2.5f) 0.6f else clamp(-diff * 3f, -1f, 1f)
        val safe = track.safeSpeed(car.spec)[(game.player.trackIndex + 3) % track.samples] * 0.9f
        val braking = brake && t > seconds + GameState.COUNTDOWN - 0.5f
        val th = if (braking || car.speed > safe) 0f else 0.8f
        val br = if (braking) 0.6f else if (car.speed > safe + 2f) 0.5f else 0f
        game.update(1f / 60f, steer, th, br)
        t += 1f / 60f
    }
    val scene = Scene(game)
    scene.updateCamera(0f, aspect)
    for ((key, glb) in scene.assets) File(out, "$key.glb").writeBytes(glb)
    // Sky cubemap mip chain (RGB float32, faces +X -X +Y -Y +Z -Z per level) and irradiance SH.
    val sky = File(out, "sky.bin")
    if (!sky.exists()) {
        val levels = SkyEnvironment.cubemap(Scene.SKY_SIZE)
        val bb = ByteBuffer.allocate(levels.sumOf { it.size } * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (l in levels) bb.asFloatBuffer().also { it.put(l) }.let { bb.position(bb.position() + l.size * 4) }
        sky.writeBytes(bb.array())
    }
    fun arr(a: FloatArray) = a.joinToString(",", "[", "]")
    var cam = floatArrayOf(scene.camX, scene.camY, scene.camZ)
    var target = floatArrayOf(scene.targetX, scene.targetY, scene.targetZ)
    var projection = scene.projection.copyOf()
    System.getenv("PREVIEW_ORBIT")?.toFloat()?.let { deg ->
        // Showroom shot: orbit the car instead of the chase camera.
        val car = game.car
        val a = car.yaw + Math.toRadians(deg.toDouble()).toFloat()
        val dist = (System.getenv("PREVIEW_DIST") ?: "6.5").toFloat()
        cam = floatArrayOf(car.x + sin(a) * dist, 1.7f, car.z + cos(a) * dist)
        target = floatArrayOf(car.x, 0.55f, car.z)
        android.opengl.Matrix.perspectiveM(projection, 0, 45f, aspect, Scene.NEAR, Scene.FAR)
    }
    // Camera matrices as the renderer builds them (the sky only turns with the camera).
    val view = FloatArray(16)
    android.opengl.Matrix.setLookAtM(view, 0, cam[0], cam[1], cam[2], target[0], target[1], target[2], 0f, 1f, 0f)
    val viewProj = FloatArray(16).also { android.opengl.Matrix.multiplyMM(it, 0, projection, 0, view, 0) }
    view[12] = 0f; view[13] = 0f; view[14] = 0f
    val skyInv = FloatArray(16).also {
        val vp = FloatArray(16)
        android.opengl.Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        android.opengl.Matrix.invertM(it, 0, vp, 0)
    }
    fun str(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    val shaders = listOf(
        "vertex" to GlShaders.VERTEX, "fragment" to GlShaders.FRAGMENT,
        "skyVertex" to GlShaders.SKY_VERTEX, "skyFragment" to GlShaders.SKY_FRAGMENT,
        "shadowVertex" to GlShaders.SHADOW_VERTEX, "shadowFragment" to GlShaders.SHADOW_FRAGMENT,
    ).joinToString(",", "{", "}") { (k, v) -> "\"$k\":${str(v)}" }
    val cars = scene.cars().joinToString(",", "[", "]") { """{"asset":"${it.asset}","model":${arr(it.model)},"braking":${it.braking}}""" }
    val wheels = scene.wheels().joinToString(",", "[", "]") { arr(it) }
    val sb = StringBuilder()
    sb.append("""{"shaders":$shaders,"viewProj":${arr(viewProj)},"skyInvViewProj":${arr(skyInv)},""")
    sb.append(""""lightVP0":${arr(scene.nearLightViewProj)},"lightVP1":${arr(scene.farLightViewProj)},"shadowSize":${Scene.SHADOW_MAP_SIZE},""")
    sb.append(""""shadowTexel":[${2f * Scene.NEAR_SHADOW_EXTENT / Scene.SHADOW_MAP_SIZE},${2f * Scene.FAR_SHADOW_EXTENT / Scene.SHADOW_MAP_SIZE}],""")
    sb.append(""""exposureValue":${Scene.EXPOSURE},"fog":${arr(Scene.FOG)},"footprints":${arr(scene.carFootprints())},"maxCars":${GlShaders.MAX_CARS},""")
    sb.append(""""cam":${arr(cam)},"target":${arr(target)},"projection":${arr(projection)},"near":${Scene.NEAR},"far":${Scene.FAR},""")
    sb.append(""""sun":[${Scene.SUN.x},${Scene.SUN.y},${Scene.SUN.z}],"sunLux":${Scene.SUN_LUX},"skyIntensity":${Scene.SKY_INTENSITY},""")
    sb.append(""""exposure":[${Scene.APERTURE},${Scene.SHUTTER},${Scene.ISO}],"skySize":${Scene.SKY_SIZE},"sh":${arr(SkyEnvironment.irradianceSh())},""")
    val fog = SkyEnvironment.fogColor
    sb.append(""""fogColor":[${fog.r},${fog.g},${fog.b}],"cars":$cars,"wheels":$wheels,""")
    sb.append(""""speed":${abs(game.car.speed) * 3.6f},"surface":"${game.surface.name}","position":${game.position},"lap":${minOf(game.player.lapsDone + 1, game.totalLaps)},"totalLaps":${game.totalLaps},"countdown":${game.countdown},"picking":${game.phase == GameState.Phase.PICKING},"car":"${game.playerSpec.brand}|${game.playerSpec.model}|${game.playerSpec.powerHp}|${game.playerSpec.zeroTo100}|${game.playerSpec.topSpeedKmh.toInt()}","win":${System.getenv("PREVIEW_WIN") != null},"winPos":${scene.playerScreenPoint(2.3f)?.let { "[${it.first},${it.second}]" } ?: "null"},"rpm":${game.gearbox.rpm},"redline":${game.playerSpec.redline},"gear":${game.gearbox.gear + 1},""")
    sb.append(""""standings":${game.standings.joinToString(",", "[", "]") { "{\"name\":\"${it.name}\",\"car\":\"${it.spec.brand}\",\"color\":${it.color},\"player\":${it.isPlayer}}" }}}""")
    File(out, "frame.json").writeText(sb.toString())
    println("exported ${scene.assets.size} assets, speed=${abs(game.car.speed) * 3.6f} surface=${game.surface.name}")
}
