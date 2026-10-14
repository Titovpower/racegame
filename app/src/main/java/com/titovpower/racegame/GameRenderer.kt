package com.titovpower.racegame

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/** Touch input written by the UI thread, read by the GL thread. */
class Controls {
    @Volatile var steer = 0f
    @Volatile var throttle = 0f
    @Volatile var brake = 0f
    /** Set by the UI to start a new race; cleared by the GL thread. */
    @Volatile var restart = false
}

/** Values the HUD shows, written by the GL thread. */
class Telemetry {
    @Volatile var speedKmh = 0f
    @Volatile var surfaceName = "STREET"
    @Volatile var lapTime = 0f
    @Volatile var lastLap = 0f
    @Volatile var bestLap = 0f
    @Volatile var standings: List<Standing> = emptyList()
    @Volatile var position = 0
    @Volatile var lap = 0
    @Volatile var totalLaps = 0
    @Volatile var countdown = 0f
    @Volatile var raceTime = 0f
    @Volatile var finished = false
}

class GameRenderer(
    private val controls: Controls,
    private val telemetry: Telemetry,
) : GLSurfaceView.Renderer, DrawSink {

    // World generation takes a moment, so it happens here on the GL thread rather than the UI thread.
    private val game by lazy { GameState(World()) }
    private val scene by lazy { Scene(game) }

    private lateinit var shader: Shader
    private val meshes = HashMap<String, GlMesh>()
    private val viewProj = FloatArray(16)
    private val mvp = FloatArray(16)
    private var aspect = 1f
    private var lastNanos = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val fog = Scene.SKY_HORIZON
        GLES20.glClearColor(fog.r, fog.g, fog.b, 1f)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        shader = Shader()
        // GL objects die with the context, so they are rebuilt each time from the cached CPU data.
        meshes.clear()
        for ((id, data) in scene.meshes) meshes[id] = GlMesh(data)
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

        if (controls.restart) {
            controls.restart = false
            game.reset()
        }
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
        telemetry.standings = game.standingsSnapshot()
        telemetry.position = game.position
        telemetry.lap = minOf(game.player.lapsDone + 1, game.totalLaps)
        telemetry.totalLaps = game.totalLaps
        telemetry.countdown = game.countdown
        telemetry.raceTime = game.raceTime
        telemetry.finished = game.player.finished

        scene.updateCamera(dt, aspect)
        scene.render(this)
    }

    override fun beginFrame(viewProj: FloatArray, camX: Float, camY: Float, camZ: Float) {
        System.arraycopy(viewProj, 0, this.viewProj, 0, 16)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(shader.program)
        val sun = Scene.SUN
        val fog = Scene.SKY_HORIZON
        val zen = Scene.SKY_ZENITH
        val bounce = Scene.GROUND_BOUNCE
        GLES20.glUniform3f(shader.u("uCamPos"), camX, camY, camZ)
        GLES20.glUniform3f(shader.u("uSunDir"), sun.x, sun.y, sun.z)
        GLES20.glUniform3f(shader.u("uFogColor"), fog.r, fog.g, fog.b)
        GLES20.glUniform3f(shader.u("uSkyHorizon"), fog.r, fog.g, fog.b)
        GLES20.glUniform3f(shader.u("uSkyZenith"), zen.r, zen.g, zen.b)
        GLES20.glUniform3f(shader.u("uGroundBounce"), bounce.r, bounce.g, bounce.b)
        GLES20.glUniform2f(shader.u("uFogRange"), Scene.FOG_NEAR, Scene.FOG_FAR)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
    }

    override fun draw(mesh: String, model: FloatArray, params: DrawParams) {
        val m = meshes[mesh] ?: return
        if (params.depthTest) GLES20.glEnable(GLES20.GL_DEPTH_TEST) else GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(params.depthWrite)
        if (params.blend) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        if (params.polygonOffset) {
            GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
            GLES20.glPolygonOffset(-2f, -4f)
        } else {
            GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
        }
        GLES20.glUniform1f(shader.u("uMode"), params.mode)
        GLES20.glUniform1f(shader.u("uAlpha"), params.alpha)
        GLES20.glUniform3f(shader.u("uTint"), params.tintR, params.tintG, params.tintB)
        GLES20.glUniform1f(shader.u("uDetail"), params.detail)
        GLES20.glUniform1f(shader.u("uDetailScale"), params.detailScale)
        Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
        GLES20.glUniformMatrix4fv(shader.u("uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(shader.u("uModel"), 1, false, model, 0)
        m.draw(shader)
    }
}
