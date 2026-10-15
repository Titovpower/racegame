package com.titovpower.racegame

import android.content.Context
import android.opengl.Matrix
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders the race with Filament, Google's physically based rendering engine: PBR materials from the glTF assets
 * [Scene] generates, a sun with cascaded soft shadows, an HDR sky for reflections and diffuse
 * light, ambient occlusion, bloom, fog, MSAA and filmic tone mapping.
 * Runs the game loop on the main thread, one step per display frame.
 */
class FilamentRenderer(
    private val context: Context,
    private val surfaceView: SurfaceView,
    private val controls: Controls,
    private val telemetry: Telemetry,
    private val settings: Settings,
) : Choreographer.FrameCallback {

    companion object {
        init {
            Filament.init()
            Gltfio.init()
        }
    }

    /**
     * The graphics API under Filament: its OpenGL ES backend, the one Filament's glTF loader and its
     * prebuilt materials are most widely used and tested with. (The Vulkan backend left the world
     * invisible on a real phone.)
     */
    val backend = Engine.Backend.OPENGL
    private val engine = Engine.create(backend)
    private val renderer: Renderer = engine.createRenderer()
    private val scene = engine.createScene()
    private val view: View = engine.createView()
    private val camera: Camera = engine.createCamera(EntityManager.get().create())
    private var swapChain: SwapChain? = null
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(context)
    private val choreographer = Choreographer.getInstance()

    private val materials = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materials, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)
    private val loaded = ArrayList<FilamentAsset>()
    private var sky: Texture? = null
    private var indirectLight: IndirectLight? = null
    private var skybox: Skybox? = null
    private val sun = EntityManager.get().create()

    /** Set once the world is generated (on a background thread) and its assets are loaded. */
    private var loop: GameLoop? = null
    private val carAssets = HashMap<String, FilamentAsset>()
    /** Car assets loaded from real models; those bring their own wheels. */
    private val customCars = HashSet<String>()
    /** Wheel nodes of real models ("wheel_0".."wheel_3", see tools/models) with their hub positions. */
    private val customWheels = HashMap<String, List<Pair<Int, FloatArray>>>()
    private val inScene = HashSet<String>()
    private val wheelAssets = ArrayList<FilamentAsset>()
    private var width = 1
    private var height = 1
    private var lastNanos = 0L
    private val projection = DoubleArray(16)
    /** The world's grass blades and leaves, shown or hidden by the "Grass & leaves" setting. */
    private var foliage = 0
    private var foliageShown = true
    /** The guardrails, in the scene only while "safe driving" is on. */
    private var fence = 0
    private var fenceShown = true
    private var appliedGraphics = -1
    /** Minimum time between rendered frames from the "Frame rate" setting (0 = every frame). */
    private var frameIntervalNanos = 0L
    /** Whether the sun was built with the "High" shadow maps. */
    private var sunHighShadows = true

    init {
        view.camera = camera
        view.scene = scene
        setupView()
        buildSun(high = true)
        scene.addEntity(sun)
        camera.setExposure(Scene.APERTURE, Scene.SHUTTER, Scene.ISO)

        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
                displayHelper.attach(renderer, surfaceView.display)
            }

            override fun onDetachedFromSurface() {
                displayHelper.detach()
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                }
                swapChain = null
            }

            override fun onResized(w: Int, h: Int) {
                width = w
                height = h
                view.viewport = Viewport(0, 0, w, h)
            }
        }
        uiHelper.attachTo(surfaceView)
        buildWorld()
    }

    private fun setupView() {
        view.fogOptions = View.FogOptions().apply {
            enabled = true
            distance = 60f
            density = 0.0007f
            height = 0f
            heightFalloff = 0.02f
            maximumOpacity = 0.85f
            val c = SkyEnvironment.fogColor
            color = floatArrayOf(c.r, c.g, c.b)
        }
        // The OpenGL options from the settings are applied on the first frame (see doFrame).
    }

    /** (Re)creates the sun; its shadow map size can only be set when the light is built. */
    private fun buildSun(high: Boolean) {
        engine.lightManager.destroy(sun)
        LightManager.Builder(LightManager.Type.SUN)
            .color(1.0f, 0.95f, 0.88f)
            .intensity(Scene.SUN_LUX)
            .direction(-Scene.SUN.x, -Scene.SUN.y, -Scene.SUN.z)
            .sunAngularRadius(1.2f)
            .castShadows(true)
            .shadowOptions(shadowOptions(high))
            .build(engine, sun)
        sunHighShadows = high
    }

    private fun shadowOptions(high: Boolean) = LightManager.ShadowOptions().apply {
        mapSize = if (high) 2048 else 1024
        shadowCascades = if (high) 3 else 2
        shadowFar = if (high) 160f else 90f
        normalBias = 1f
        constantBias = 0.001f
    }

    /** Applies the OpenGL graphics options from the settings panel. */
    private fun applyGraphics() {
        appliedGraphics = settings.graphicsVersion
        fun choice(o: GraphicsOption) = settings.graphics(o)

        // Resolution: "Auto" lowers it when the phone can't keep the frame rate up.
        val scale = floatArrayOf(0f, 1f, 0.75f, 0.5f)[choice(GraphicsOption.RESOLUTION)]
        view.dynamicResolutionOptions = View.DynamicResolutionOptions().apply {
            enabled = scale != 1f
            minScale = if (scale == 0f) 0.6f else scale
            maxScale = if (scale == 0f) 1f else scale
        }

        val aa = choice(GraphicsOption.ANTI_ALIASING)
        view.antiAliasing = if (aa == 0) View.AntiAliasing.NONE else View.AntiAliasing.FXAA
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply {
            enabled = aa == 2
            sampleCount = 4
        }

        val shadows = choice(GraphicsOption.SHADOWS)
        val lm = engine.lightManager
        view.setShadowingEnabled(shadows != 0)
        if (shadows != 0 && (shadows == 2) != sunHighShadows) buildSun(high = shadows == 2)
        val light = lm.getInstance(sun)
        if (light != 0) lm.setShadowCaster(light, shadows != 0)

        view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply {
            enabled = choice(GraphicsOption.AMBIENT_OCCLUSION) == 1
            radius = 0.6f
        }
        view.bloomOptions = View.BloomOptions().apply {
            enabled = choice(GraphicsOption.BLOOM) == 1
            strength = 0.08f
        }

        val showFoliage = choice(GraphicsOption.GRASS) == 1
        if (foliage != 0 && showFoliage != foliageShown) {
            if (showFoliage) scene.addEntity(foliage) else scene.removeEntity(foliage)
        }
        foliageShown = showFoliage

        val fps = intArrayOf(30, 60, 0)[choice(GraphicsOption.FRAME_RATE)]
        // A little under the interval, so a 60 Hz display isn't held to every other vsync at 60.
        frameIntervalNanos = if (fps == 0) 0L else 1_000_000_000L / fps - 4_000_000L
    }

    /** Generates the world and its assets off the main thread, then loads them into Filament. */
    private fun buildWorld() {
        telemetry.loading = true
        Thread {
            try {
                generateAndLoad()
            } catch (t: Throwable) {
                fail(t)
            }
        }.start()
    }

    /** Shows what went wrong on screen instead of an empty world. */
    private fun fail(t: Throwable) {
        telemetry.error = "Could not build the world: ${t.javaClass.simpleName}: ${t.message ?: ""}"
        telemetry.loading = false
    }

    private fun generateAndLoad() {
        val game = GameState(World())
        val scene = Scene(game)
        val assets = scene.assets
        val skyLevels = SkyEnvironment.cubemap(Scene.SKY_SIZE)
        val sh = SkyEnvironment.irradianceSh()
        surfaceView.post {
            if (destroyed) return@post
            try {
                createSky(skyLevels, sh)
                for ((key, glb) in assets) {
                    when (key) {
                        Scene.WORLD -> {
                            val a = load(CustomModels.world(context) ?: glb)
                            this.scene.addEntities(a.entities)
                            foliage = a.getFirstEntityByName(Scene.FOLIAGE_NODE)
                            fence = a.getFirstEntityByName(Scene.FENCE_NODE)
                            fenceShown = true
                            foliageShown = true
                            appliedGraphics = -1
                        }
                        Scene.WHEEL -> repeat(game.racers.size * 4) { wheelAssets.add(load(glb)) }
                        else -> {
                            // "car_<style>_<paint>": a real model dropped into the assets replaces the generated one.
                            val (_, style, paint) = key.split("_")
                            val custom = CustomModels.car(context, style)
                            if (custom != null) {
                                val a = load(custom)
                                repaint(a, paint.toInt(16))
                                customCars.add(key)
                                customWheels[key] = (0 until 4).mapNotNull { k ->
                                    val e = a.getFirstEntityByName("wheel_$k")
                                    if (e == 0) return@mapNotNull null
                                    val rest = FloatArray(16)
                                    engine.transformManager.getTransform(engine.transformManager.getInstance(e), rest)
                                    e to floatArrayOf(rest[12], rest[13], rest[14], k.toFloat())
                                }
                                carAssets[key] = a
                            } else {
                                carAssets[key] = load(glb)
                            }
                        }
                    }
                }
                for (a in wheelAssets) this.scene.addEntities(a.entities)
                loop = GameLoop(game, scene, controls, telemetry)
                telemetry.loading = false
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    private fun load(glb: ByteArray): FilamentAsset {
        val buffer = ByteBuffer.allocateDirect(glb.size).order(ByteOrder.nativeOrder())
        buffer.put(glb).flip()
        val asset = assetLoader.createAsset(buffer) ?: throw IllegalStateException("Could not load glTF asset")
        resourceLoader.loadResources(asset)
        asset.releaseSourceData()
        val rm = engine.renderableManager
        // Grass blades and leaf cards are too small to matter in the shadow maps; the crowns cast the tree shadows.
        val foliage = asset.getFirstEntityByName(Scene.FOLIAGE_NODE)
        for (e in asset.renderableEntities) {
            val i = rm.getInstance(e)
            rm.setCastShadows(i, e != foliage)
            rm.setReceiveShadows(i, true)
        }
        loaded.add(asset)
        return asset
    }

    /** Gives every material whose name contains "paint" the racer's color. */
    private fun repaint(asset: FilamentAsset, rgb: Int) {
        val rm = engine.renderableManager
        val c = Rgb.hex(rgb)
        fun lin(v: Float) = Math.pow(v.toDouble(), 2.2).toFloat()
        for (e in asset.renderableEntities) {
            val ri = rm.getInstance(e)
            for (p in 0 until rm.getPrimitiveCount(ri)) {
                val mi = rm.getMaterialInstanceAt(ri, p)
                if (mi.name.contains("paint", ignoreCase = true)) {
                    mi.setParameter("baseColorFactor", lin(c.r), lin(c.g), lin(c.b), 1f)
                }
            }
        }
    }

    private fun createSky(levels: List<FloatArray>, sh: FloatArray) {
        val size = Scene.SKY_SIZE
        val tex = Texture.Builder()
            .width(size)
            .height(size)
            .levels(levels.size)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.R11F_G11F_B10F)
            .build(engine)
        var s = size
        for ((level, data) in levels.withIndex()) {
            val bytes = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            bytes.asFloatBuffer().put(data)
            val faceBytes = s * s * 3 * 4
            val offsets = IntArray(6) { it * faceBytes }
            tex.setImage(engine, level, Texture.PixelBufferDescriptor(bytes, Texture.Format.RGB, Texture.Type.FLOAT), offsets)
            s = maxOf(1, s / 2)
        }
        sky = tex
        indirectLight = IndirectLight.Builder()
            .reflections(tex)
            .irradiance(3, sh)
            .intensity(Scene.SKY_INTENSITY)
            .build(engine)
        skybox = Skybox.Builder().environment(tex).intensity(Scene.SKY_INTENSITY).build(engine)
        scene.indirectLight = indirectLight
        scene.skybox = skybox
    }

    fun resume() {
        lastNanos = 0L
        choreographer.postFrameCallback(this)
    }

    fun pause() {
        choreographer.removeFrameCallback(this)
    }

    @Volatile private var destroyed = false

    fun destroy() {
        destroyed = true
        choreographer.removeFrameCallback(this)
        uiHelper.detach()
        for (a in loaded) assetLoader.destroyAsset(a)
        materials.destroyMaterials()
        materials.destroy()
        assetLoader.destroy()
        resourceLoader.destroy()
        indirectLight?.let { engine.destroyIndirectLight(it) }
        skybox?.let { engine.destroySkybox(it) }
        sky?.let { engine.destroyTexture(it) }
        engine.destroyEntity(sun)
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(camera.entity)
        EntityManager.get().destroy(camera.entity)
        engine.destroy()
    }

    override fun doFrame(frameTimeNanos: Long) {
        choreographer.postFrameCallback(this)
        if (settings.graphicsVersion != appliedGraphics) applyGraphics()
        if (lastNanos != 0L && frameTimeNanos - lastNanos < frameIntervalNanos) return
        val dt = if (lastNanos == 0L) 0f else (frameTimeNanos - lastNanos) / 1e9f
        lastNanos = frameTimeNanos
        val loop = loop
        if (loop != null) {
            loop.step(dt, width.toFloat() / height.coerceAtLeast(1))
            updateScene(loop.scene, loop.game.safeDriving)
        }
        val chain = swapChain ?: return
        if (!uiHelper.isReadyToRender) return
        if (renderer.beginFrame(chain, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    private fun updateScene(s: Scene, safeDriving: Boolean) {
        if (fence != 0 && safeDriving != fenceShown) {
            if (safeDriving) scene.addEntity(fence) else scene.removeEntity(fence)
            fenceShown = safeDriving
        }
        val tm = engine.transformManager
        val rm = engine.renderableManager
        // Cars: only the assets in use this frame are in the scene (the player can switch models).
        val poses = s.cars()
        val wanted = poses.map { it.asset }.toSet()
        for (key in inScene.toList()) if (key !in wanted) {
            carAssets[key]?.let { scene.removeEntities(it.entities) }
            inScene.remove(key)
        }
        for (pose in poses) {
            val asset = carAssets[pose.asset] ?: continue
            if (inScene.add(pose.asset)) scene.addEntities(asset.entities)
            tm.setTransform(tm.getInstance(asset.root), pose.model)
            val lights = asset.getFirstEntityByName(Scene.LIGHTS_NODE)
            if (lights != 0) {
                val ri = rm.getInstance(lights)
                if (ri != 0) {
                    // Real models' tail lights stay lit, brighter under braking.
                    val glow = if (pose.braking) BRAKE_GLOW else if (pose.asset in customCars) TAIL_GLOW else 0f
                    rm.getMaterialInstanceAt(ri, 0).setParameter("emissiveFactor", glow, glow * 0.05f, glow * 0.03f)
                }
            }
        }
        for (pose in poses) {
            for ((e, hub) in customWheels[pose.asset] ?: continue) {
                // Spin about the axle; the front pair (wheel_0 and wheel_1) also steers.
                Matrix.setIdentityM(wheelMatrix, 0)
                Matrix.translateM(wheelMatrix, 0, hub[0], hub[1], hub[2])
                if (hub[3] < 2f) Matrix.rotateM(wheelMatrix, 0, pose.wheelSteer, 0f, 1f, 0f)
                Matrix.rotateM(wheelMatrix, 0, pose.wheelSpin, 1f, 0f, 0f)
                tm.setTransform(tm.getInstance(e), wheelMatrix)
            }
        }
        for ((k, m) in s.wheels().withIndex()) {
            if (k >= wheelAssets.size) break
            val wheel = wheelAssets[k]
            // Real models have their own wheels: hide ours under them.
            if (poses[k / 4].asset in customCars) {
                Matrix.scaleM(m, 0, 0f, 0f, 0f)
            }
            tm.setTransform(tm.getInstance(wheel.root), m)
        }
        for (k in 0 until 16) projection[k] = s.projection[k].toDouble()
        camera.setCustomProjection(projection, Scene.NEAR.toDouble(), Scene.FAR.toDouble())
        camera.lookAt(
            s.camX.toDouble(), s.camY.toDouble(), s.camZ.toDouble(),
            s.targetX.toDouble(), s.targetY.toDouble(), s.targetZ.toDouble(),
            0.0, 1.0, 0.0,
        )
    }

    private val wheelMatrix = FloatArray(16)
    private val BRAKE_GLOW = 40f
    private val TAIL_GLOW = 3f
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
