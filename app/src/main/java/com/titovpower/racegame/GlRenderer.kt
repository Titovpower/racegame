package com.titovpower.racegame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/**
 * Renders the race directly with OpenGL ES 3.0 (see [GlShaders] for the lighting): the glTF
 * assets [Scene] generates (or real models from the app's assets), an HDR sky cubemap, two
 * cascades of sun shadow maps and 4x MSAA. Runs the game loop on the GL thread, one step per frame.
 */
class GlRenderer(
    private val context: Context,
    private val controls: Controls,
    private val telemetry: Telemetry,
) : GLSurfaceView.Renderer {

    /** Everything built off the GL thread: game, scene, parsed models, decoded textures, sky. */
    private class Content(
        val loop: GameLoop,
        val models: Map<String, GltfModel>,
        val bitmaps: Map<String, List<Bitmap?>>,
        val customCars: Set<String>,
        val skyLevels: List<FloatArray>,
        val sh: FloatArray,
    )

    @Volatile private var content: Content? = null

    init {
        Thread {
            val game = GameState(World())
            val scene = Scene(game)
            val models = HashMap<String, GltfModel>()
            val custom = HashSet<String>()
            for ((key, glb) in scene.assets) {
                models[key] = when (key) {
                    Scene.WORLD -> Gltf.parse(CustomModels.world(context) ?: glb)
                    Scene.WHEEL -> Gltf.parse(glb)
                    else -> {
                        // "car_<style>_<paint>": a real model dropped into the assets replaces the generated one.
                        val (_, style, paint) = key.split("_")
                        val real = CustomModels.car(context, style)
                        if (real != null) {
                            custom.add(key)
                            repaint(Gltf.parse(real), paint.toInt(16))
                        } else {
                            Gltf.parse(glb)
                        }
                    }
                }
            }
            val opts = BitmapFactory.Options().apply { inPremultiplied = false }
            val bitmaps = models.mapValues { (_, m) ->
                m.images.map { if (it.isEmpty()) null else BitmapFactory.decodeByteArray(it, 0, it.size, opts) }
            }
            content = Content(
                GameLoop(game, scene, controls, telemetry),
                models,
                bitmaps,
                custom,
                SkyEnvironment.cubemap(Scene.SKY_SIZE),
                SkyEnvironment.irradianceSh(),
            )
        }.start()
    }

    /** Gives every material whose name contains "paint" the racer's color. */
    private fun repaint(model: GltfModel, rgb: Int): GltfModel {
        val c = Rgb.hex(rgb)
        fun lin(v: Float) = Math.pow(v.toDouble(), 2.2).toFloat()
        val mats = model.materials.map { m ->
            if (!m.name.contains("paint", ignoreCase = true)) m else GltfMaterial(
                m.name, floatArrayOf(lin(c.r), lin(c.g), lin(c.b), 1f), m.metallic, m.roughness, m.emissive,
                maxOf(m.clearcoat, 1f), m.clearcoatRoughness, -1, m.doubleSided, m.blend,
            )
        }
        return GltfModel(model.primitives, mats, model.images)
    }

    // GL state, rebuilt whenever the context is (re)created.
    private var program = 0
    private var skyProgram = 0
    private var shadowProgram = 0
    private var skyTex = 0
    private var skyVao = 0
    private val shadowFbo = IntArray(2)
    private val shadowTex = IntArray(2)
    private var shadowsOn = false
    private var farDrawn = -1
    private var uploaded: Content? = null
    private val gpu = HashMap<String, GlModel>()
    private val uniforms = HashMap<String, Int>()
    private var width = 1
    private var height = 1
    private var lastNanos = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(GlShaders.VERTEX, GlShaders.FRAGMENT)
        skyProgram = link(GlShaders.SKY_VERTEX, GlShaders.SKY_FRAGMENT)
        shadowProgram = link(GlShaders.SHADOW_VERTEX, GlShaders.SHADOW_FRAGMENT)
        uniforms.clear()
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        skyVao = ids[0]
        shadowsOn = createShadowMaps()
        uploaded = null
        gpu.clear()
        skyTex = 0
        farDrawn = -1
        lastNanos = 0L
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
    }

    fun onResume() {
        lastNanos = 0L
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else (now - lastNanos) / 1e9f
        lastNanos = now
        val c = content
        if (c == null) {
            val fog = SkyEnvironment.fogColor
            GLES30.glClearColor(fog.r, fog.g, fog.b, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        if (uploaded !== c) upload(c)
        c.loop.step(dt, width.toFloat() / height.coerceAtLeast(1))
        draw(c, c.loop.scene)
    }

    private fun upload(c: Content) {
        for ((key, model) in c.models) gpu[key] = GlModel(model, c.bitmaps[key] ?: emptyList())
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        skyTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, skyTex)
        var s = Scene.SKY_SIZE
        for ((level, data) in c.skyLevels.withIndex()) {
            val face = s * s * 3
            val fb = ByteBuffer.allocateDirect(face * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (f in 0 until 6) {
                fb.clear()
                fb.put(data, f * face, face).position(0)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_X + f, level, GLES30.GL_R11F_G11F_B10F, s, s, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, fb)
            }
            s = maxOf(1, s / 2)
        }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MAX_LEVEL, c.skyLevels.size - 1)
        uploaded = c
    }

    /** Two depth textures with hardware depth comparison (sampler2DShadow). */
    private fun createShadowMaps(): Boolean {
        val size = Scene.SHADOW_MAP_SIZE
        GLES30.glGenTextures(2, shadowTex, 0)
        GLES30.glGenFramebuffers(2, shadowFbo, 0)
        for (k in 0 until 2) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex[k])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_DEPTH_COMPONENT24, size, size)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo[k])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowTex[k], 0)
            GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
            GLES30.glReadBuffer(GLES30.GL_NONE)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                return false
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return true
    }

    private class Item(val model: GlModel, val matrix: FloatArray, val emissive: FloatArray? = null)

    private fun draw(c: Content, s: Scene) {
        // What to draw: the world, the cars in use, and the wheels (hidden under real models, which bring their own).
        val items = ArrayList<Item>()
        val world = gpu[Scene.WORLD]
        val cars = ArrayList<Item>()
        val poses = s.cars()
        for (pose in poses) {
            val m = gpu[pose.asset] ?: continue
            cars.add(Item(m, pose.model, if (pose.braking) BRAKE_GLOW else null))
        }
        val wheel = gpu[Scene.WHEEL]
        if (wheel != null) for ((k, m) in s.wheels().withIndex()) {
            if (poses[k / 4].asset !in c.customCars) cars.add(Item(wheel, m))
        }
        if (world != null) items.add(Item(world, IDENTITY))
        items.addAll(cars)

        // Shadow maps: the near cascade every frame, the far one (world only) when it moves.
        if (shadowsOn) {
            GLES30.glUseProgram(shadowProgram)
            GLES30.glViewport(0, 0, Scene.SHADOW_MAP_SIZE, Scene.SHADOW_MAP_SIZE)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(1.5f, 3f)
            GLES30.glDepthMask(true)
            shadowPass(0, s.nearLightViewProj, items)
            if (farDrawn != s.farCascadeVersion && world != null) {
                shadowPass(1, s.farLightViewProj, listOf(Item(world, IDENTITY)))
                farDrawn = s.farCascadeVersion
            }
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT or GLES30.GL_COLOR_BUFFER_BIT)

        // Sky behind everything.
        GLES30.glUseProgram(skyProgram)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(false)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, skyTex)
        GLES30.glUniform1i(u(skyProgram, "uSky"), 0)
        GLES30.glUniform1f(u(skyProgram, "uSkyScale"), Scene.SKY_INTENSITY * Scene.EXPOSURE)
        GLES30.glUniformMatrix4fv(u(skyProgram, "uInvViewProj"), 1, false, s.skyInvViewProj, 0)
        GLES30.glBindVertexArray(skyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDepthMask(true)

        // Scene.
        val p = program
        GLES30.glUseProgram(p)
        GLES30.glUniformMatrix4fv(u(p, "uViewProj"), 1, false, s.viewProj, 0)
        GLES30.glUniformMatrix4fv(u(p, "uLightVP0"), 1, false, s.nearLightViewProj, 0)
        GLES30.glUniformMatrix4fv(u(p, "uLightVP1"), 1, false, s.farLightViewProj, 0)
        val texel0 = 2f * Scene.NEAR_SHADOW_EXTENT / Scene.SHADOW_MAP_SIZE
        val texel1 = 2f * Scene.FAR_SHADOW_EXTENT / Scene.SHADOW_MAP_SIZE
        GLES30.glUniform2f(u(p, "uNormalBias"), texel0 * 1.5f, texel1 * 1.5f)
        GLES30.glUniform2f(u(p, "uShadowTexel"), 1f / Scene.SHADOW_MAP_SIZE, 1f / Scene.SHADOW_MAP_SIZE)
        GLES30.glUniform1f(u(p, "uShadowOn"), if (shadowsOn) 1f else 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, skyTex)
        GLES30.glUniform1i(u(p, "uSky"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex[0])
        GLES30.glUniform1i(u(p, "uShadow0"), 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex[1])
        GLES30.glUniform1i(u(p, "uShadow1"), 3)
        GLES30.glUniform1i(u(p, "uTex"), 0)
        GLES30.glUniform1f(u(p, "uSkyMaxLod"), (c.skyLevels.size - 1).toFloat())
        GLES30.glUniform1f(u(p, "uSkyScale"), Scene.SKY_INTENSITY * Scene.EXPOSURE)
        GLES30.glUniform3fv(u(p, "uSh"), 9, c.sh, 0)
        val sun = Scene.SUN
        val lux = Scene.SUN_LUX * Scene.EXPOSURE
        GLES30.glUniform3f(u(p, "uSunDir"), sun.x, sun.y, sun.z)
        GLES30.glUniform3f(u(p, "uSunColor"), SUN_COLOR[0] * lux, SUN_COLOR[1] * lux, SUN_COLOR[2] * lux)
        GLES30.glUniform3f(u(p, "uCamPos"), s.camX, s.camY, s.camZ)
        GLES30.glUniform4f(u(p, "uFog"), Scene.FOG[0], Scene.FOG[1], Scene.FOG[2], Scene.FOG[3])
        val feet = s.carFootprints()
        val count = minOf(feet.size / 4, GlShaders.MAX_CARS)
        if (count > 0) GLES30.glUniform4fv(u(p, "uCars"), count, feet, 0)
        GLES30.glUniform1i(u(p, "uCarCount"), count)
        for (pass in 0 until 2) {
            // Opaque first, then see-through materials (glass on real models) blended on top.
            val blend = pass == 1
            if (blend) {
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glDepthMask(false)
            }
            for (item in items) item.model.draw(p, this, item.matrix, item.emissive, blend)
        }
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
    }

    private fun shadowPass(k: Int, lightVP: FloatArray, items: List<Item>) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo[k])
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUniformMatrix4fv(u(shadowProgram, "uLightVP"), 1, false, lightVP, 0)
        for (item in items) item.model.drawDepth(u(shadowProgram, "uModel"), item.matrix)
    }

    fun u(prog: Int, name: String): Int = uniforms.getOrPut("$prog/$name") { GLES30.glGetUniformLocation(prog, name) }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val sh = GLES30.glCreateShader(type)
            GLES30.glShaderSource(sh, src)
            GLES30.glCompileShader(sh)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("Shader compile failed: " + GLES30.glGetShaderInfoLog(sh))
            return sh
        }
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(prog, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Program link failed: " + GLES30.glGetProgramInfoLog(prog))
        return prog
    }

    companion object {
        private val IDENTITY = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        /** Lit brake lamps (added light, after exposure). */
        private val BRAKE_GLOW = floatArrayOf(6f, 0.12f, 0.06f)
        private val SUN_COLOR = floatArrayOf(1.0f, 0.95f, 0.88f)

        /** Configures [view] for OpenGL ES 3 with 4x MSAA where the device offers it. */
        fun setUp(view: GLSurfaceView) {
            view.setEGLContextClientVersion(3)
            view.setEGLConfigChooser(MultisampleConfigChooser())
            view.preserveEGLContextOnPause = true
        }
    }
}

/** A [GltfModel] in GPU buffers: one vertex array per primitive, textures per image. */
private class GlModel(private val model: GltfModel, bitmaps: List<Bitmap?>) {
    private class Prim(val vao: Int, val count: Int, val material: GltfMaterial, val matrix: FloatArray, val mirrored: Boolean)

    private val prims = ArrayList<Prim>()
    private val textures = IntArray(model.images.size)

    init {
        for ((k, bmp) in bitmaps.withIndex()) {
            if (bmp == null) continue
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            textures[k] = ids[0]
            val px = ByteBuffer.allocateDirect(bmp.width * bmp.height * 4).order(ByteOrder.nativeOrder())
            val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
            argb.copyPixelsToBuffer(px)
            px.position(0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_SRGB8_ALPHA8, bmp.width, bmp.height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, px)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        }
        for (p in model.primitives) {
            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1, ids, 0)
            val vao = ids[0]
            GLES30.glBindVertexArray(vao)
            attribute(0, p.positions, 3)
            attribute(1, p.normals, 3)
            if (p.colors != null) attribute(2, p.colors, 4) else GLES30.glVertexAttrib4f(2, 1f, 1f, 1f, 1f)
            if (p.uvs != null) attribute(3, p.uvs, 2) else GLES30.glVertexAttrib4f(3, 0f, 0f, 0f, 1f)
            val ib = ByteBuffer.allocateDirect(p.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            ib.put(p.indices).position(0)
            GLES30.glGenBuffers(1, ids, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[0])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, p.indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
            GLES30.glBindVertexArray(0)
            prims.add(Prim(vao, p.indices.size, model.materials[p.material], p.matrix, determinant3(p.matrix) < 0f))
        }
    }

    private fun attribute(loc: Int, data: FloatArray, size: Int) {
        val fb: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data).position(0)
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(loc)
        GLES30.glVertexAttribPointer(loc, size, GLES30.GL_FLOAT, false, 0, 0)
    }

    private val world = FloatArray(16)

    fun draw(prog: Int, r: GlRenderer, matrix: FloatArray, emissive: FloatArray?, blendPass: Boolean) {
        val outerMirrored = determinant3(matrix) < 0f
        for (p in prims) {
            val m = p.material
            if (m.blend != blendPass) continue
            Matrix.multiplyMM(world, 0, matrix, 0, p.matrix, 0)
            GLES30.glUniformMatrix4fv(r.u(prog, "uModel"), 1, false, world, 0)
            GLES30.glUniform4fv(r.u(prog, "uBaseColor"), 1, m.baseColor, 0)
            GLES30.glUniform1f(r.u(prog, "uMetallic"), m.metallic)
            GLES30.glUniform1f(r.u(prog, "uRoughness"), m.roughness)
            GLES30.glUniform1f(r.u(prog, "uClearcoat"), m.clearcoat)
            GLES30.glUniform1f(r.u(prog, "uClearcoatRoughness"), m.clearcoatRoughness)
            // Brake glow lights the materials that are lamps (emissive in the asset).
            val glow = if (emissive != null && m.name == "LAMP") emissive else m.emissive
            GLES30.glUniform3f(r.u(prog, "uEmissive"), glow[0], glow[1], glow[2])
            val tex = if (m.image >= 0) textures[m.image] else 0
            GLES30.glUniform1f(r.u(prog, "uHasTex"), if (tex != 0) 1f else 0f)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            if (m.doubleSided) GLES30.glDisable(GLES30.GL_CULL_FACE) else GLES30.glEnable(GLES30.GL_CULL_FACE)
            // Mirrored transforms (left wheels) turn the triangles around.
            GLES30.glFrontFace(if (p.mirrored != outerMirrored) GLES30.GL_CW else GLES30.GL_CCW)
            GLES30.glBindVertexArray(p.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, p.count, GLES30.GL_UNSIGNED_INT, 0)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glFrontFace(GLES30.GL_CCW)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    fun drawDepth(modelLoc: Int, matrix: FloatArray) {
        for (p in prims) {
            if (p.material.blend) continue
            Matrix.multiplyMM(world, 0, matrix, 0, p.matrix, 0)
            GLES30.glUniformMatrix4fv(modelLoc, 1, false, world, 0)
            GLES30.glBindVertexArray(p.vao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, p.count, GLES30.GL_UNSIGNED_INT, 0)
        }
        GLES30.glBindVertexArray(0)
    }
}

private fun determinant3(m: FloatArray): Float =
    m[0] * (m[5] * m[10] - m[6] * m[9]) - m[4] * (m[1] * m[10] - m[2] * m[9]) + m[8] * (m[1] * m[6] - m[2] * m[5])

/** Picks an OpenGL ES 3 config with 4x multisampling, falling back to none. */
private class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        for (samples in intArrayOf(4, 0)) for (depth in intArrayOf(24, 16)) {
            val attrs = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, depth,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0,
                EGL10.EGL_SAMPLES, samples,
                EGL10.EGL_NONE,
            )
            val count = IntArray(1)
            val configs = arrayOfNulls<EGLConfig>(1)
            if (egl.eglChooseConfig(display, attrs, configs, 1, count) && count[0] > 0) return configs[0]!!
        }
        throw IllegalStateException("No OpenGL ES 3 configuration")
    }

    companion object {
        const val EGL_OPENGL_ES3_BIT = 0x40
    }
}

/** Optional glTF models dropped into the app's assets (see README), used instead of the generated ones. */
object CustomModels {
    fun world(context: Context): ByteArray? = read(context, "models/world.glb")

    /** A car model for body style [style] (lambo, 911, m4, amggt, r8, vantage). */
    fun car(context: Context, style: String): ByteArray? = read(context, "models/cars/$style.glb")

    private fun read(context: Context, path: String): ByteArray? = try {
        context.assets.open(path).use { it.readBytes() }
    } catch (e: java.io.IOException) {
        null
    }
}
