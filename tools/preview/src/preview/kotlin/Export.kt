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
    for ((id, data) in scene.meshes) {
        val bb = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(data)
        File(out, "$id.bin").writeBytes(bb.array())
    }
    val sb = StringBuilder()
    fun arr(a: FloatArray) = a.joinToString(",", "[", "]")
    fun col(c: Rgb) = "[${c.r},${c.g},${c.b}]"
    var vp = FloatArray(16)
    var cam = floatArrayOf(0f, 0f, 0f)
    val draws = ArrayList<String>()
    val casters = ArrayList<String>()
    var lightVp = FloatArray(16)
    scene.render(object : DrawSink {
        override fun beginShadows(lightViewProj: FloatArray) { lightVp = lightViewProj.copyOf() }
        override fun drawShadowCaster(mesh: String, model: FloatArray) {
            casters.add("""{"mesh":"$mesh","model":${arr(model)}}""")
        }
        override fun beginFrame(viewProj: FloatArray, camX: Float, camY: Float, camZ: Float, lightViewProj: FloatArray) {
            vp = viewProj.copyOf(); cam = floatArrayOf(camX, camY, camZ)
        }
        override fun draw(mesh: String, model: FloatArray, p: DrawParams) {
            draws.add("""{"mesh":"$mesh","model":${arr(model)},"mode":${p.mode},"alpha":${p.alpha},"tint":[${p.tintR},${p.tintG},${p.tintB}],"detail":${p.detail},"detailScale":${p.detailScale},"blend":${p.blend},"depthTest":${p.depthTest},"depthWrite":${p.depthWrite},"polygonOffset":${p.polygonOffset}}""")
        }
    })
    System.getenv("PREVIEW_ORBIT")?.toFloat()?.let { deg ->
        // Showroom shot: orbit the car instead of the chase camera.
        val car = game.car
        val a = car.yaw + Math.toRadians(deg.toDouble()).toFloat()
        val dist = (System.getenv("PREVIEW_DIST") ?: "6.5").toFloat()
        val cx = car.x + sin(a) * dist; val cz = car.z + cos(a) * dist; val cy = 1.7f
        val proj = FloatArray(16); val view = FloatArray(16)
        android.opengl.Matrix.perspectiveM(proj, 0, 45f, aspect, 0.3f, 1500f)
        android.opengl.Matrix.setLookAtM(view, 0, cx, cy, cz, car.x, 0.55f, car.z, 0f, 1f, 0f)
        android.opengl.Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        cam = floatArrayOf(cx, cy, cz)
        // Sky follows the camera.
        val sky = draws[0]
        draws[0] = sky.replace(Regex("\"model\":\\[[^\\]]*\\]"), "\"model\":[1,0,0,0,0,1,0,0,0,0,1,0,$cx,$cy,$cz,1]")
    }
    sb.append("""{"viewProj":${arr(vp)},"cam":${arr(cam)},"sun":[${Scene.SUN.x},${Scene.SUN.y},${Scene.SUN.z}],"horizon":${col(Scene.SKY_HORIZON)},"zenith":${col(Scene.SKY_ZENITH)},"bounce":${col(Scene.GROUND_BOUNCE)},"fog":[${Scene.FOG_NEAR},${Scene.FOG_FAR}],""")
    sb.append(""""speed":${abs(game.car.speed) * 3.6f},"surface":"${game.surface.name}","position":${game.position},"lap":${minOf(game.player.lapsDone + 1, game.totalLaps)},"totalLaps":${game.totalLaps},"countdown":${game.countdown},"picking":${game.phase == GameState.Phase.PICKING},"car":"${game.playerSpec.brand}|${game.playerSpec.model}|${game.playerSpec.powerHp}|${game.playerSpec.zeroTo100}|${game.playerSpec.topSpeedKmh.toInt()}","win":${System.getenv("PREVIEW_WIN") != null},"winPos":${scene.playerScreenPoint(2.3f)?.let { "[${it.first},${it.second}]" } ?: "null"},"standings":${game.standings.joinToString(",", "[", "]") { "{\"name\":\"${it.name}\",\"car\":\"${it.spec.brand}\",\"color\":${it.color},\"player\":${it.isPlayer}}" }},"lightViewProj":${arr(lightVp)},"shadowSize":${Scene.SHADOW_MAP_SIZE},"casters":[${casters.joinToString(",")}],"draws":[${draws.joinToString(",")}]}""")
    File(out, "frame.json").writeText(sb.toString())
    File(out, "shader.vert").writeText(ShaderSource.VERTEX)
    File(out, "shader.frag").writeText(ShaderSource.FRAGMENT)
    File(out, "shadow.vert").writeText(ShadowShaderSource.VERTEX)
    File(out, "shadow.frag").writeText(ShadowShaderSource.FRAGMENT)
    println("exported ${draws.size} draws, speed=${abs(game.car.speed) * 3.6f} surface=${game.surface.name}")
}
