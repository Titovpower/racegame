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
    val track = game.world.track
    var t = 0f
    while (t < seconds) {
        val car = game.car
        val target = (track.nearest(car.x, car.z).index + 12) % track.samples
        val desired = atan2(track.xs[target] - car.x, track.zs[target] - car.z)
        var diff = desired - car.yaw
        while (diff > PI) diff -= (2 * PI).toFloat()
        while (diff < -PI) diff += (2 * PI).toFloat()
        val steer = if (offroad && t > seconds - 2.5f) 0.6f else clamp(-diff * 3f, -1f, 1f)
        game.update(1f / 60f, steer, if (brake && t > seconds - 0.5f) 0f else 0.8f, if (brake && t > seconds - 0.5f) 0.6f else 0f)
        t += 1f / 60f
    }
    val scene = Scene(game)
    scene.updateCamera(0f, aspect)
    for ((id, data) in scene.meshes) {
        val bb = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(data)
        File(out, "${id.name}.bin").writeBytes(bb.array())
    }
    val sb = StringBuilder()
    fun arr(a: FloatArray) = a.joinToString(",", "[", "]")
    fun col(c: Rgb) = "[${c.r},${c.g},${c.b}]"
    var vp = FloatArray(16)
    var cam = floatArrayOf(0f, 0f, 0f)
    val draws = ArrayList<String>()
    scene.render(object : DrawSink {
        override fun beginFrame(viewProj: FloatArray, camX: Float, camY: Float, camZ: Float) {
            vp = viewProj.copyOf(); cam = floatArrayOf(camX, camY, camZ)
        }
        override fun draw(mesh: MeshId, model: FloatArray, p: DrawParams) {
            draws.add("""{"mesh":"${mesh.name}","model":${arr(model)},"mode":${p.mode},"alpha":${p.alpha},"tint":[${p.tintR},${p.tintG},${p.tintB}],"detail":${p.detail},"detailScale":${p.detailScale},"blend":${p.blend},"depthTest":${p.depthTest},"depthWrite":${p.depthWrite},"polygonOffset":${p.polygonOffset}}""")
        }
    }, braking = brake)
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
    sb.append(""""speed":${abs(game.car.speed) * 3.6f},"surface":"${game.surface.name}","draws":[${draws.joinToString(",")}]}""")
    File(out, "frame.json").writeText(sb.toString())
    File(out, "shader.vert").writeText(ShaderSource.VERTEX)
    File(out, "shader.frag").writeText(ShaderSource.FRAGMENT)
    println("exported ${draws.size} draws, speed=${abs(game.car.speed) * 3.6f} surface=${game.surface.name}")
}
