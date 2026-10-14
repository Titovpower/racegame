package com.titovpower.racegame

import android.opengl.Matrix
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** One car to draw this frame: which asset, where, and whether its brake lights are on. */
class CarPose(val asset: String, val model: FloatArray, val braking: Boolean)

/**
 * What to draw and from where, independent of the rendering engine: the 3D assets (as glTF
 * binaries, generated from the procedural meshes), the chase camera, and each frame's car and
 * wheel transforms, the sun's shadow cascades. The OpenGL renderer and the offline preview both
 * consume this.
 */
class Scene(private val game: GameState) {
    /**
     * glTF assets by key: [WORLD], [WHEEL], and one per car body style and paint
     * (see [carKey]). The player can pick any model, so their paint gets every style.
     */
    val assets: Map<String, ByteArray> by lazy {
        val w = game.world
        val m = linkedMapOf(
            WORLD to Glb.write(
                listOf(
                    GlbPart("ground", SceneMeshes.ground(w), Surfacing.GRASS),
                    GlbPart("road", SceneMeshes.road(w.track), Surfacing.ASPHALT),
                    GlbPart("props", SceneMeshes.props(w)),
                    GlbPart("vegetation", SceneMeshes.vegetation(w)),
                    GlbPart("foliage", SceneMeshes.foliage(w), Surfacing.FOLIAGE),
                ),
            ),
            WHEEL to Glb.write(listOf(GlbPart("wheel", CarModel.wheel()))),
        )
        for ((st, color) in carVariants()) {
            m[carKey(st, color)] = Glb.write(
                listOf(
                    GlbPart("body", CarModel.body(st, Rgb.hex(color))),
                    GlbPart(LIGHTS_NODE, CarModel.tailLights(st), Surfacing.LAMP),
                ),
            )
        }
        m
    }

    private fun carVariants(): List<Pair<CarStyle, Int>> {
        val styles = CarSpec.ALL.map { CarModel.styleFor(it) }.distinct()
        return (game.racers.map { CarModel.styleFor(it.spec) to it.color } + styles.map { it to GameState.PLAYER_COLOR }).distinct()
    }

    fun carKey(st: CarStyle, color: Int) = "car_" + st.key + "_" + Integer.toHexString(color)

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    /** Projection x view, including the vertical [shift]; used for HUD placement. */
    val viewProj = FloatArray(16)
    private val shifted = FloatArray(16)
    /** Projection matrix including the vertical [shift] (column-major). */
    val projection get() = shifted
    private val shiftM = FloatArray(16)

    // Camera, as the renderer needs it.
    var camX = 0f
        private set
    var camY = 0f
        private set
    var camZ = 0f
        private set
    var targetX = 0f
        private set
    var targetY = 0f
        private set
    var targetZ = 0f
        private set
    /** Vertical field of view, degrees. */
    var fov = 60f
        private set
    var aspect = 2f
        private set
    /** Vertical image offset in normalized device units (2 = full screen height). */
    var shift = SCREEN_SHIFT
        private set

    private var camYaw = 0f
    private var camInit = false
    private var orbit = 0f

    /** Moves the chase camera towards its target; dt = 0 snaps it there. */
    fun updateCamera(dt: Float, aspect: Float) {
        this.aspect = aspect
        val car = game.car
        if (game.phase == GameState.Phase.PICKING) {
            // Showroom: slowly circle the player's car so the chosen model can be admired.
            orbit += dt * 0.35f
            val a = car.yaw + 2.4f + orbit
            camX = car.x + sin(a) * 6.8f
            camZ = car.z + cos(a) * 6.8f
            camY = 1.9f
            targetX = car.x; targetY = 0.6f; targetZ = car.z
            fov = 50f
            shift = SCREEN_SHIFT * 0.6f
            camInit = false
        } else {
            if (!camInit || dt == 0f) {
                camYaw = car.yaw
            } else {
                // Camera heading lags behind the car, giving a sense of turning.
                var diff = car.yaw - camYaw
                while (diff > PI) diff -= (2 * PI).toFloat()
                while (diff < -PI) diff += (2 * PI).toFloat()
                camYaw += diff * (1f - exp(-dt * 4f))
            }
            val dist = 7.2f + abs(car.speed) * 0.04f
            val height = 2.6f
            val tx = car.x - sin(camYaw) * dist
            val tz = car.z - cos(camYaw) * dist
            if (!camInit || dt == 0f) {
                camX = tx; camY = height; camZ = tz
                camInit = true
            } else {
                val k = 1f - exp(-dt * 10f)
                camX += (tx - camX) * k
                camY += (height - camY) * k
                camZ += (tz - camZ) * k
            }
            fov = 58f + clamp(abs(car.speed) / 58f, 0f, 1f) * 12f
            shift = SCREEN_SHIFT
            targetX = car.x + sin(camYaw) * 4f
            targetY = 1.0f
            targetZ = car.z + cos(camYaw) * 4f
        }
        Matrix.perspectiveM(proj, 0, fov, aspect, NEAR, FAR)
        // Shift the image up so the car sits above the steering wheel overlay.
        Matrix.setIdentityM(shiftM, 0)
        Matrix.translateM(shiftM, 0, 0f, shift, 0f)
        Matrix.multiplyMM(shifted, 0, shiftM, 0, proj, 0)
        Matrix.setLookAtM(view, 0, camX, camY, camZ, targetX, targetY, targetZ, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, shifted, 0, view, 0)
        // The sky only turns with the camera: drop the translation, then invert.
        System.arraycopy(view, 0, tmp16, 0, 16)
        tmp16[12] = 0f; tmp16[13] = 0f; tmp16[14] = 0f
        Matrix.multiplyMM(tmp16b, 0, shifted, 0, tmp16, 0)
        Matrix.invertM(skyInvViewProj, 0, tmp16b, 0)
        updateShadows(if (game.phase == GameState.Phase.PICKING) 0f else 16f)
    }

    private val tmp16 = FloatArray(16)
    private val tmp16b = FloatArray(16)
    /** Inverse of the camera's rotation-only view-projection, for drawing the sky. */
    val skyInvViewProj = FloatArray(16)

    /** Sun shadow cascades: a sharp one around where the camera looks, and a coarse wide one. */
    val nearLightViewProj = FloatArray(16)
    val farLightViewProj = FloatArray(16)
    /** Changes whenever the far cascade moves; it only needs redrawing then (it holds the static world). */
    var farCascadeVersion = 0
        private set
    private var farX = Float.NaN
    private var farZ = Float.NaN
    private val lightView = FloatArray(16).also {
        Matrix.setLookAtM(it, 0, SUN.x * 500f, SUN.y * 500f, SUN.z * 500f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    private fun updateShadows(ahead: Float) {
        val car = game.car
        cascade(nearLightViewProj, car.x + sin(camYaw) * ahead, car.z + cos(camYaw) * ahead, NEAR_SHADOW_EXTENT, 150f)
        val fx = Math.round(car.x / FAR_SHADOW_STEP) * FAR_SHADOW_STEP
        val fz = Math.round(car.z / FAR_SHADOW_STEP) * FAR_SHADOW_STEP
        if (fx != farX || fz != farZ) {
            farX = fx
            farZ = fz
            cascade(farLightViewProj, fx, fz, FAR_SHADOW_EXTENT, 300f)
            farCascadeVersion++
        }
    }

    /** Orthographic sun view of a square [extent] meters around (x, z), snapped to whole texels so edges don't shimmer. */
    private fun cascade(out: FloatArray, x: Float, z: Float, extent: Float, depth: Float) {
        tmp4[0] = x; tmp4[1] = 0f; tmp4[2] = z; tmp4[3] = 1f
        Matrix.multiplyMV(clip4, 0, lightView, 0, tmp4, 0)
        val texel = 2f * extent / SHADOW_MAP_SIZE
        val cx = Math.round(clip4[0] / texel) * texel
        val cy = Math.round(clip4[1] / texel) * texel
        Matrix.orthoM(tmp16, 0, cx - extent, cx + extent, cy - extent, cy + extent, -clip4[2] - depth, -clip4[2] + depth)
        Matrix.multiplyMM(out, 0, tmp16, 0, lightView, 0)
    }

    /** Per car: x, z, sin(yaw), cos(yaw), for the soft contact shadows under them. */
    fun carFootprints(): FloatArray {
        val out = FloatArray(game.racers.size * 4)
        for ((k, r) in game.racers.withIndex()) {
            out[k * 4] = r.car.x; out[k * 4 + 1] = r.car.z
            out[k * 4 + 2] = sin(r.car.yaw); out[k * 4 + 3] = cos(r.car.yaw)
        }
        return out
    }

    private val tmp4 = FloatArray(4)
    private val clip4 = FloatArray(4)

    /**
     * Where a point [height] meters above the player's car lands on screen, as fractions of the
     * width and height from the top-left; null when it is behind the camera.
     */
    fun playerScreenPoint(height: Float): Pair<Float, Float>? {
        val car = game.car
        tmp4[0] = car.x; tmp4[1] = height; tmp4[2] = car.z; tmp4[3] = 1f
        Matrix.multiplyMV(clip4, 0, viewProj, 0, tmp4, 0)
        if (clip4[3] <= 0.01f) return null
        return (clip4[0] / clip4[3] * 0.5f + 0.5f) to (0.5f - clip4[1] / clip4[3] * 0.5f)
    }

    /** Car bodies this frame (with a little pitch under acceleration and roll in corners). */
    fun cars(): List<CarPose> = game.racers.map { r ->
        val car = r.car
        val m = FloatArray(16)
        carMatrix(car, m)
        val pitch = clamp(-car.longAccel * 0.25f, -2.5f, 2.5f)
        val roll = clamp(-car.latAccel * 0.18f, -3.5f, 3.5f)
        Matrix.translateM(m, 0, 0f, 0.04f, 0f)
        Matrix.rotateM(m, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(m, 0, roll, 0f, 0f, 1f)
        CarPose(carKey(CarModel.styleFor(r.spec), r.color), m, r.braking)
    }

    /** Four wheel transforms per racer, in racer order (front-left, front-right, rear-left, rear-right). */
    fun wheels(): List<FloatArray> {
        val out = ArrayList<FloatArray>(game.racers.size * 4)
        val base = FloatArray(16)
        for (r in game.racers) {
            val car = r.car
            val st = CarModel.styleFor(r.spec)
            carMatrix(car, base)
            val spinDeg = Math.toDegrees(car.wheelSpin.toDouble()).toFloat()
            val steerDeg = -Math.toDegrees(car.wheelAngle.toDouble()).toFloat()
            for (k in 0 until 4) {
                // The mesh's outer face is +x, so left-side wheels are mirrored.
                val wx = if (k % 2 == 0) st.wheelX else -st.wheelX
                val wz = if (k < 2) st.frontAxle else st.rearAxle
                val m = base.copyOf()
                // The wheel mesh scaled to this axle's real tire size.
                val tyre = if (k < 2) r.spec.frontTyre else r.spec.rearTyre
                val size = tyre.radius / CarModel.WHEEL_RADIUS
                Matrix.translateM(m, 0, wx, tyre.radius, wz)
                if (wz > 0f) Matrix.rotateM(m, 0, steerDeg, 0f, 1f, 0f)
                Matrix.rotateM(m, 0, spinDeg, 1f, 0f, 0f)
                Matrix.scaleM(m, 0, (if (wx < 0f) -1f else 1f) * tyre.width / 0.3f, size, size)
                out.add(m)
            }
        }
        return out
    }

    private fun carMatrix(car: CarPhysics, m: FloatArray) {
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, car.x, 0f, car.z)
        Matrix.rotateM(m, 0, Math.toDegrees(car.yaw.toDouble()).toFloat(), 0f, 1f, 0f)
    }

    companion object {
        const val WORLD = "world"
        const val WHEEL = "wheel"
        const val LIGHTS_NODE = "lights"

        const val SCREEN_SHIFT = 0.32f
        const val NEAR = 0.5f
        const val FAR = 2000f

        /** Direction towards the sun. */
        val SUN = V3(0.55f, 0.52f, 0.65f).normalized()

        // Photometric setup (physical units, scaled by the camera exposure).
        /** Sun illuminance, lux. */
        const val SUN_LUX = 100_000f
        /** Sky (image-based light and skybox) intensity, cd/m² for sky radiance 1. */
        const val SKY_INTENSITY = 30_000f
        const val APERTURE = 16f
        const val SHUTTER = 1f / 125f
        const val ISO = 100f
        /** Cubemap resolution of the sky. */
        const val SKY_SIZE = 256
        /** Camera exposure: 1 / (1.2 x 2^EV100), EV100 from aperture, shutter and ISO. */
        val EXPOSURE = (1.0 / (1.2 * APERTURE * APERTURE / SHUTTER * 100.0 / ISO)).toFloat()

        /** Height fog: start distance (m), density, height falloff, maximum opacity. */
        val FOG = floatArrayOf(60f, 0.0007f, 0.02f, 0.85f)

        const val SHADOW_MAP_SIZE = 2048
        /** Half-widths of the shadow cascades, meters. */
        const val NEAR_SHADOW_EXTENT = 36f
        const val FAR_SHADOW_EXTENT = 230f
        const val FAR_SHADOW_STEP = 20f
    }
}
