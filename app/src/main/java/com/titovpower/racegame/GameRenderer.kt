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
    /** Car picker: models to step forward (+) or back (-), consumed by the GL thread. */
    val pick = java.util.concurrent.atomic.AtomicInteger(0)
    /** Set by the UI when START is pressed in the car picker. */
    @Volatile var start = false
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
    /** Race time at which the player finished. */
    @Volatile var finishTime = 0f
    /** True while the player is choosing a car before the race. */
    @Volatile var picking = false
    @Volatile var carSpec: CarSpec = CarSpec.PORSCHE
    /** Screen position (fractions of width/height) just above the player's car, or negative if off screen. */
    @Volatile var carScreenX = -1f
    @Volatile var carScreenY = -1f
}

class GameRenderer(
    private val controls: Controls,
    private val telemetry: Telemetry,
) : GLSurfaceView.Renderer, DrawSink {

    // World generation takes a moment, so it happens here on the GL thread rather than the UI thread.
    private val game by lazy { GameState(World()) }
    private val scene by lazy { Scene(game) }

    private lateinit var shader: Shader
    private lateinit var shadowShader: Shader
    private var shadowFbo = 0
    private var shadowTex = 0
    private var shadowSize = 0
    private var screenW = 1
    private var screenH = 1
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
        shadowShader = Shader(ShadowShaderSource.VERTEX, ShadowShaderSource.FRAGMENT)
        createShadowMap()
        // GL objects die with the context, so they are rebuilt each time from the cached CPU data.
        meshes.clear()
        for ((id, data) in scene.meshes) meshes[id] = GlMesh(data)
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        screenW = width
        screenH = height
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
        val pick = controls.pick.getAndSet(0)
        if (pick != 0) game.cycleCar(pick)
        if (controls.start) {
            controls.start = false
            game.startRace()
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
        telemetry.finishTime = game.player.finishTime
        telemetry.picking = game.phase == GameState.Phase.PICKING
        telemetry.carSpec = game.playerSpec

        scene.updateCamera(dt, aspect)
        val above = scene.playerScreenPoint(2.3f)
        telemetry.carScreenX = above?.first ?: -1f
        telemetry.carScreenY = above?.second ?: -1f
        scene.render(this)
    }

    /** Sun shadow map: RGBA color texture holding packed depth, plus a depth renderbuffer. */
    private fun createShadowMap() {
        val max = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
        shadowSize = minOf(Scene.SHADOW_MAP_SIZE, max[0])
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        shadowTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, shadowSize, shadowSize, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenRenderbuffers(1, ids, 0)
        val depth = ids[0]
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depth)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, shadowSize, shadowSize)
        GLES20.glGenFramebuffers(1, ids, 0)
        shadowFbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, shadowFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, shadowTex, 0)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depth)
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            // No shadows on this device rather than a broken frame.
            GLES20.glDeleteFramebuffers(1, intArrayOf(shadowFbo), 0)
            shadowFbo = 0
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private val lightViewProj = FloatArray(16)

    override fun beginShadows(lightViewProj: FloatArray) {
        if (shadowFbo == 0) return
        System.arraycopy(lightViewProj, 0, this.lightViewProj, 0, 16)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, shadowFbo)
        GLES20.glViewport(0, 0, shadowSize, shadowSize)
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glUseProgram(shadowShader.program)
        // Only the position attribute is read in this pass.
        for (k in 0 until 3) GLES20.glDisableVertexAttribArray(k)
    }

    override fun drawShadowCaster(mesh: String, model: FloatArray) {
        if (shadowFbo == 0) return
        val m = meshes[mesh] ?: return
        Matrix.multiplyMM(mvp, 0, lightViewProj, 0, model, 0)
        GLES20.glUniformMatrix4fv(shadowShader.u("uMvp"), 1, false, mvp, 0)
        m.draw(shadowShader)
    }

    override fun beginFrame(viewProj: FloatArray, camX: Float, camY: Float, camZ: Float, lightViewProj: FloatArray) {
        System.arraycopy(viewProj, 0, this.viewProj, 0, 16)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, screenW, screenH)
        val clear = Scene.SKY_HORIZON
        GLES20.glClearColor(clear.r, clear.g, clear.b, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(shader.program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTex)
        GLES20.glUniform1i(shader.u("uShadowMap"), 0)
        GLES20.glUniform1f(shader.u("uShadowOn"), if (shadowFbo != 0) 1f else 0f)
        GLES20.glUniform1f(shader.u("uShadowTexel"), 1f / shadowSize.coerceAtLeast(1))
        GLES20.glUniformMatrix4fv(shader.u("uLightVP"), 1, false, lightViewProj, 0)
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
