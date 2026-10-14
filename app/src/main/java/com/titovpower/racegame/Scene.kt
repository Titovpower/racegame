package com.titovpower.racegame

import android.opengl.Matrix
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

enum class MeshId { SKY, GROUND, ROAD, SHADOWS, PROPS, VEGETATION, CAR_BODY, CAR_LIGHTS, WHEEL, CAR_SHADOW }

/** Render state for one draw call. */
class DrawParams {
    var mode = 0f
    var alpha = 1f
    var tintR = 1f
    var tintG = 1f
    var tintB = 1f
    var detail = 0f
    var detailScale = 0f
    var blend = false
    var depthTest = true
    var depthWrite = true
    var polygonOffset = false

    fun reset(): DrawParams {
        mode = 0f; alpha = 1f; tintR = 1f; tintG = 1f; tintB = 1f
        detail = 0f; detailScale = 0f
        blend = false; depthTest = true; depthWrite = true; polygonOffset = false
        return this
    }
}

/** Receives the draw calls of one frame (the GL renderer, or the offline preview). */
interface DrawSink {
    fun beginFrame(viewProj: FloatArray, camX: Float, camY: Float, camZ: Float)

    /** [mesh] is a key of [Scene.meshes]. */
    fun draw(mesh: String, model: FloatArray, params: DrawParams)
}

/**
 * Everything about what to draw and from where, independent of the graphics API:
 * mesh data, chase camera and the per-frame list of draw calls.
 */
class Scene(private val game: GameState) {
    /** CPU-side mesh data, built once, keyed by [MeshId] name (car bodies: one per paint color). */
    val meshes: Map<String, FloatArray> by lazy {
        val w = game.world
        val m = linkedMapOf(
            MeshId.SKY.name to SceneMeshes.sky(),
            MeshId.GROUND.name to SceneMeshes.ground(w),
            MeshId.ROAD.name to SceneMeshes.road(w.track),
            MeshId.SHADOWS.name to SceneMeshes.shadows(w),
            MeshId.PROPS.name to SceneMeshes.props(w),
            MeshId.VEGETATION.name to SceneMeshes.vegetation(w),
            MeshId.CAR_LIGHTS.name to CarModel.tailLights(),
            MeshId.WHEEL.name to CarModel.wheel(),
            MeshId.CAR_SHADOW.name to CarModel.shadow(),
        )
        for (r in game.racers) m[bodyKey(r.color)] = CarModel.body(Rgb.hex(r.color))
        m
    }

    private fun bodyKey(color: Int) = MeshId.CAR_BODY.name + "_" + Integer.toHexString(color)

    private val proj = FloatArray(16)
    private val shiftedProj = FloatArray(16)
    private val shift = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val carModel = FloatArray(16)
    private val params = DrawParams()

    var camX = 0f
        private set
    var camY = 0f
        private set
    var camZ = 0f
        private set
    private var camYaw = 0f
    private var camInit = false

    /** Moves the chase camera towards its target; dt = 0 snaps it there. */
    fun updateCamera(dt: Float, aspect: Float) {
        val car = game.car
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

        val fov = 58f + clamp(abs(car.speed) / 58f, 0f, 1f) * 12f
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.4f, 1500f)
        // Shift the image up so the car sits above the steering wheel overlay.
        Matrix.setIdentityM(shift, 0)
        Matrix.translateM(shift, 0, 0f, SCREEN_SHIFT, 0f)
        Matrix.multiplyMM(shiftedProj, 0, shift, 0, proj, 0)
        val lookX = car.x + sin(camYaw) * 4f
        val lookZ = car.z + cos(camYaw) * 4f
        Matrix.setLookAtM(view, 0, camX, camY, camZ, lookX, 1.0f, lookZ, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, shiftedProj, 0, view, 0)
    }

    fun render(sink: DrawSink) {
        sink.beginFrame(viewProj, camX, camY, camZ)

        // Sky: centered on the camera, behind everything.
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, camX, camY, camZ)
        params.reset().apply { mode = 1f; depthTest = false; depthWrite = false }
        sink.draw(MeshId.SKY.name, model, params)

        Matrix.setIdentityM(model, 0)
        params.reset().apply { detail = 0.7f; detailScale = 0.6f }
        sink.draw(MeshId.GROUND.name, model, params)
        // The road sits a few cm above the grass; polygon offset stops it flickering at a distance.
        params.reset().apply { detail = 0.35f; detailScale = 3f; polygonOffset = true }
        sink.draw(MeshId.ROAD.name, model, params)
        params.reset().apply { mode = 2f; blend = true; depthWrite = false; polygonOffset = true }
        sink.draw(MeshId.SHADOWS.name, model, params)
        params.reset()
        sink.draw(MeshId.PROPS.name, model, params)
        sink.draw(MeshId.VEGETATION.name, model, params)

        for (r in game.racers) drawCar(sink, r)
    }

    private fun drawCar(sink: DrawSink, racer: Racer) {
        val car = racer.car
        Matrix.setIdentityM(carModel, 0)
        Matrix.translateM(carModel, 0, car.x, 0f, car.z)
        Matrix.rotateM(carModel, 0, Math.toDegrees(car.yaw.toDouble()).toFloat(), 0f, 1f, 0f)

        // Soft shadow (before body roll/pitch so it stays on the ground).
        System.arraycopy(carModel, 0, model, 0, 16)
        params.reset().apply { mode = 2f; blend = true; depthWrite = false; polygonOffset = true }
        sink.draw(MeshId.CAR_SHADOW.name, model, params)

        // Wheels; the mesh's outer face is +x, so left-side wheels are mirrored.
        val spinDeg = Math.toDegrees(car.wheelSpin.toDouble()).toFloat()
        val steerDeg = -Math.toDegrees(car.wheelAngle.toDouble()).toFloat()
        params.reset()
        for (k in 0 until 4) {
            val wx = if (k % 2 == 0) CarModel.WHEEL_X else -CarModel.WHEEL_X
            val wz = if (k < 2) CarModel.FRONT_AXLE else CarModel.REAR_AXLE
            System.arraycopy(carModel, 0, model, 0, 16)
            Matrix.translateM(model, 0, wx, CarPhysics.WHEEL_RADIUS, wz)
            if (wz > 0f) Matrix.rotateM(model, 0, steerDeg, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, spinDeg, 1f, 0f, 0f)
            if (wx < 0f) Matrix.scaleM(model, 0, -1f, 1f, 1f)
            sink.draw(MeshId.WHEEL.name, model, params)
        }

        // Body with a little pitch under acceleration and roll in corners.
        System.arraycopy(carModel, 0, model, 0, 16)
        val pitch = clamp(-car.longAccel * 0.25f, -2.5f, 2.5f)
        val roll = clamp(-car.latAccel * 0.18f, -3.5f, 3.5f)
        Matrix.translateM(model, 0, 0f, 0.04f, 0f)
        Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, roll, 0f, 0f, 1f)
        params.reset()
        sink.draw(bodyKey(racer.color), model, params)
        if (racer.braking) {
            params.reset().apply { mode = 2f; tintR = 1.6f; tintG = 1.6f; tintB = 1.6f }
        } else {
            params.reset()
        }
        sink.draw(MeshId.CAR_LIGHTS.name, model, params)
    }

    companion object {
        /** Vertical image offset in normalized device units (2 = full screen height). */
        const val SCREEN_SHIFT = 0.32f

        val SUN = V3(0.55f, 0.52f, 0.65f).normalized()
        val SKY_ZENITH = Rgb.hex(0x2F6FC4)
        val SKY_HORIZON = Rgb.hex(0xC9DDEE)
        val GROUND_BOUNCE = Rgb.hex(0x5E6B3A)
        const val FOG_NEAR = 200f
        const val FOG_FAR = 900f
    }
}
