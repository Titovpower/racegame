package com.titovpower.racegame

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Touch input written by the UI thread, read by the GL thread. */
class Controls {
    @Volatile var steer = 0f
    @Volatile var throttle = 0f
    @Volatile var brake = 0f
}

/** Values the HUD shows, written by the GL thread. */
class Telemetry {
    @Volatile var speedKmh = 0f
    @Volatile var surfaceName = "STREET"
    @Volatile var lapTime = 0f
    @Volatile var lastLap = 0f
    @Volatile var bestLap = 0f
}

class GameRenderer(
    private val controls: Controls,
    private val telemetry: Telemetry,
) : GLSurfaceView.Renderer {

    private lateinit var shader: Shader
    private lateinit var ground: GlMesh
    private lateinit var road: GlMesh
    private lateinit var gantry: GlMesh
    private lateinit var vegetation: GlMesh
    private lateinit var carBody: GlMesh
    private lateinit var wheel: GlMesh
    private lateinit var shadow: GlMesh
    private lateinit var sky: GlMesh

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val carModel = FloatArray(16)
    private val mvp = FloatArray(16)
    private val shift = FloatArray(16)
    private val shiftedProj = FloatArray(16)

    private var aspect = 1f
    private var lastNanos = 0L

    // Chase camera state.
    private var camX = 0f
    private var camY = 0f
    private var camZ = 0f
    private var camYaw = 0f
    private var camInit = false

    // Sun direction (normalized), from above and slightly to the side.
    private val light = V3(0.45f, 0.8f, 0.35f).normalized()
    private val fog = SceneMeshes.SKY_HORIZON

    // CPU-side meshes are built once; GL buffers are (re)created per GL context.
    // World generation takes a moment, so it happens here on the GL thread rather than the UI thread.
    private val game by lazy { GameState(World()) }
    private val world get() = game.world
    private val groundData by lazy { SceneMeshes.ground(world) }
    private val roadData by lazy { SceneMeshes.road(world.track) }
    private val gantryData by lazy { SceneMeshes.gantry(world.track) }
    private val vegetationData by lazy { SceneMeshes.vegetation(world) }
    private val carData by lazy { SceneMeshes.carBody() }
    private val wheelData by lazy { SceneMeshes.wheel() }
    private val shadowData by lazy { SceneMeshes.shadow() }
    private val skyData by lazy { SceneMeshes.sky() }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(fog.r, fog.g, fog.b, 1f)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        shader = Shader()
        ground = GlMesh(groundData)
        road = GlMesh(roadData)
        gantry = GlMesh(gantryData)
        vegetation = GlMesh(vegetationData)
        carBody = GlMesh(carData)
        wheel = GlMesh(wheelData)
        shadow = GlMesh(shadowData)
        sky = GlMesh(skyData)
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.05f)
        lastNanos = now

        // Simulate in small fixed steps for stable physics.
        var remaining = dt
        while (remaining > 0f) {
            val step = minOf(remaining, 1f / 120f)
            game.update(step, controls.steer, controls.throttle, controls.brake)
            remaining -= step
        }
        val car = game.car
        telemetry.speedKmh = abs(car.speed) * 3.6f
        telemetry.surfaceName = game.surface.name
        telemetry.lapTime = game.lapTime
        telemetry.lastLap = game.lastLap
        telemetry.bestLap = game.bestLap

        updateCamera(dt)
        draw()
    }

    private fun updateCamera(dt: Float) {
        val car = game.car
        if (!camInit) {
            camYaw = car.yaw
        } else {
            // Camera heading lags behind the car, giving a sense of turning.
            var diff = car.yaw - camYaw
            while (diff > PI) diff -= (2 * PI).toFloat()
            while (diff < -PI) diff += (2 * PI).toFloat()
            camYaw += diff * (1f - exp(-dt * 4f))
        }
        val dist = 7.5f + abs(car.speed) * 0.04f
        val height = 2.8f
        val tx = car.x - sin(camYaw) * dist
        val tz = car.z - cos(camYaw) * dist
        if (!camInit) {
            camX = tx; camY = height; camZ = tz
            camInit = true
        } else {
            val k = 1f - exp(-dt * 10f)
            camX += (tx - camX) * k
            camY += (height - camY) * k
            camZ += (tz - camZ) * k
        }

        val fov = 60f + clamp(abs(car.speed) / 58f, 0f, 1f) * 12f
        Matrix.perspectiveM(proj, 0, fov, aspect, 0.5f, 1400f)
        // Shift the image up so the car sits above the steering wheel overlay.
        Matrix.setIdentityM(shift, 0)
        Matrix.translateM(shift, 0, 0f, SCREEN_SHIFT, 0f)
        Matrix.multiplyMM(shiftedProj, 0, shift, 0, proj, 0)
        val lookX = car.x + sin(camYaw) * 4f
        val lookZ = car.z + cos(camYaw) * 4f
        Matrix.setLookAtM(view, 0, camX, camY, camZ, lookX, 1.0f, lookZ, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProj, 0, shiftedProj, 0, view, 0)
    }

    private fun draw() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(shader.program)
        GLES20.glUniform3f(shader.uCamPos, camX, camY, camZ)
        GLES20.glUniform3f(shader.uLightDir, light.x, light.y, light.z)
        GLES20.glUniform3f(shader.uFogColor, fog.r, fog.g, fog.b)
        GLES20.glUniform2f(shader.uFogRange, 180f, 750f)
        GLES20.glUniform1f(shader.uAlpha, 1f)

        // Sky: centered on the camera, behind everything.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUniform1f(shader.uUnlit, 1f)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, camX, 0f, camZ)
        drawMesh(sky)
        GLES20.glUniform1f(shader.uUnlit, 0f)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        Matrix.setIdentityM(model, 0)
        drawMesh(ground)
        // The road sits a few cm above the grass; polygon offset stops it flickering at a distance.
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glPolygonOffset(-2f, -4f)
        drawMesh(road)
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
        drawMesh(gantry)
        drawMesh(vegetation)

        drawCar()
    }

    private fun drawCar() {
        val car = game.car
        Matrix.setIdentityM(carModel, 0)
        Matrix.translateM(carModel, 0, car.x, 0f, car.z)
        Matrix.rotateM(carModel, 0, Math.toDegrees(car.yaw.toDouble()).toFloat(), 0f, 1f, 0f)

        // Blob shadow (before body roll/pitch so it stays on the ground).
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUniform1f(shader.uAlpha, 0.45f)
        GLES20.glUniform1f(shader.uUnlit, 1f)
        System.arraycopy(carModel, 0, model, 0, 16)
        drawMesh(shadow)
        GLES20.glUniform1f(shader.uUnlit, 0f)
        GLES20.glUniform1f(shader.uAlpha, 1f)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)

        // Wheels.
        val wheelX = 0.86f
        val frontZ = 1.38f
        val rearZ = -1.32f
        val spinDeg = Math.toDegrees(car.wheelSpin.toDouble()).toFloat()
        val steerDeg = -Math.toDegrees(car.wheelAngle.toDouble()).toFloat()
        for ((wx, wz) in arrayOf(wheelX to frontZ, -wheelX to frontZ, wheelX to rearZ, -wheelX to rearZ)) {
            System.arraycopy(carModel, 0, model, 0, 16)
            Matrix.translateM(model, 0, wx, CarPhysics.WHEEL_RADIUS, wz)
            if (wz > 0f) Matrix.rotateM(model, 0, steerDeg, 0f, 1f, 0f)
            Matrix.rotateM(model, 0, spinDeg, 1f, 0f, 0f)
            drawMesh(wheel)
        }

        // Body with a little pitch under acceleration and roll in corners.
        System.arraycopy(carModel, 0, model, 0, 16)
        val pitch = clamp(-car.longAccel * 0.25f, -2.5f, 2.5f)
        val roll = clamp(-car.latAccel * 0.18f, -3.5f, 3.5f)
        Matrix.translateM(model, 0, 0f, 0.05f, 0f)
        Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, roll, 0f, 0f, 1f)
        drawMesh(carBody)
    }

    private fun drawMesh(mesh: GlMesh) {
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
        GLES20.glUniformMatrix4fv(shader.uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(shader.uModel, 1, false, model, 0)
        mesh.draw(shader)
    }

    companion object {
        /** Vertical image offset in normalized device units (2 = full screen height). */
        const val SCREEN_SHIFT = 0.32f
    }
}
