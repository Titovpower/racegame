package com.titovpower.racegame

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Procedural sky as an HDR environment: the skybox, the reflections on paint and glass, and the
 * diffuse sky light (as spherical harmonics) all come from this one function, so lighting matches
 * what you see. Values are linear radiance relative to the engine's sky intensity.
 */
object SkyEnvironment {
    private val ZENITH = lin(Rgb.hex(0x2A63B8))
    private val HORIZON = lin(Rgb.hex(0xBFD6EC))
    private val GROUND = lin(Rgb.hex(0x56633A))
    private val SUN_COLOR = Rgb(1f, 0.93f, 0.82f)

    private fun lin(c: Rgb) = Rgb(c.r.pow(2.2f), c.g.pow(2.2f), c.b.pow(2.2f))

    /** Sky radiance in direction (x, y, z), normalized. [withSun] off for the diffuse light (the sun is a separate light). */
    fun radiance(x: Float, y: Float, z: Float, withSun: Boolean = true, clouds: Boolean = true): Rgb {
        val sun = Scene.SUN
        if (y < 0f) {
            // Below the horizon: hazy ground bounce fading from the horizon color.
            return HORIZON.scale(0.7f).mix(GROUND.scale(0.6f), clamp(-y * 4f, 0f, 1f))
        }
        val h = y
        var c = HORIZON.mix(ZENITH, sqrt(h).coerceAtMost(1f))
        val s = max(0f, x * sun.x + y * sun.y + z * sun.z)
        // Glow around the sun (Mie scattering), and the disc itself.
        c = Rgb(
            c.r + SUN_COLOR.r * (s.pow(8f) * 0.35f + s.pow(64f) * 0.6f),
            c.g + SUN_COLOR.g * (s.pow(8f) * 0.35f + s.pow(64f) * 0.6f),
            c.b + SUN_COLOR.b * (s.pow(8f) * 0.35f + s.pow(64f) * 0.6f),
        )
        if (clouds && h > 0.02f) {
            val u = x / (h + 0.08f) * 1.2f
            val v = z / (h + 0.08f) * 1.2f
            val n = Noise.value(u, v) * 0.5f + Noise.value(u * 2.3f + 7f, v * 2.3f + 7f) * 0.3f +
                Noise.value(u * 5.1f - 3f, v * 5.1f - 3f) * 0.2f
            val cover = smooth((n - 0.5f) / 0.28f) * smooth(h / 0.2f)
            val lit = 0.75f + 0.35f * smooth((n - 0.55f) / 0.25f) + s.pow(6f) * 0.6f
            c = c.mix(Rgb(lit, lit, lit * 1.02f), cover * 0.92f)
        }
        if (withSun && s > SUN_COS) {
            val k = 400f * smooth((s - SUN_COS) / (1f - SUN_COS) * 4f)
            c = Rgb(c.r + SUN_COLOR.r * k, c.g + SUN_COLOR.g * k, c.b + SUN_COLOR.b * k)
        }
        return c
    }

    private const val SUN_COS = 0.99993f

    private fun smooth(t: Float): Float {
        val c = clamp(t, 0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Direction through texel (x, y) of cube face [face] (+X, -X, +Y, -Y, +Z, -Z), OpenGL layout. */
    fun direction(face: Int, x: Int, y: Int, size: Int): V3 {
        val u = 2f * (x + 0.5f) / size - 1f
        val v = 2f * (y + 0.5f) / size - 1f
        return when (face) {
            0 -> V3(1f, -v, -u)
            1 -> V3(-1f, -v, u)
            2 -> V3(u, 1f, v)
            3 -> V3(u, -1f, -v)
            4 -> V3(u, -v, 1f)
            else -> V3(-u, -v, -1f)
        }.normalized()
    }

    /**
     * Cubemap mip chain as packed RGB floats, all six faces per level (face-major), from
     * [size] down to 1. Lower levels are box-filtered: rougher surfaces see a blurrier sky.
     */
    fun cubemap(size: Int): List<FloatArray> {
        val levels = ArrayList<FloatArray>()
        val top = FloatArray(6 * size * size * 3)
        for (f in 0 until 6) for (y in 0 until size) for (x in 0 until size) {
            val d = direction(f, x, y, size)
            val c = radiance(d.x, d.y, d.z)
            val o = ((f * size + y) * size + x) * 3
            top[o] = c.r; top[o + 1] = c.g; top[o + 2] = c.b
        }
        levels.add(top)
        var s = size
        var prev = top
        while (s > 1) {
            val n = s / 2
            val next = FloatArray(6 * n * n * 3)
            for (f in 0 until 6) for (y in 0 until n) for (x in 0 until n) for (c in 0 until 3) {
                fun at(xx: Int, yy: Int) = prev[((f * s + yy) * s + xx) * 3 + c]
                next[((f * n + y) * n + x) * 3 + c] =
                    (at(2 * x, 2 * y) + at(2 * x + 1, 2 * y) + at(2 * x, 2 * y + 1) + at(2 * x + 1, 2 * y + 1)) * 0.25f
            }
            levels.add(next)
            prev = next
            s = n
        }
        return levels
    }

    /**
     * Diffuse sky light as 9 RGB spherical-harmonics coefficients in the form the shader evaluates:
     * already convolved with the cosine lobe, divided by pi, with the basis constants folded in.
     */
    fun irradianceSh(): FloatArray {
        val sh = DoubleArray(27)
        val size = 32
        var weightSum = 0.0
        for (f in 0 until 6) for (y in 0 until size) for (x in 0 until size) {
            val u = 2.0 * (x + 0.5) / size - 1
            val v = 2.0 * (y + 0.5) / size - 1
            val w = 4.0 / ((1 + u * u + v * v).pow(1.5)) // texel solid angle (unnormalized)
            val d = direction(f, x, y, size)
            val c = radiance(d.x, d.y, d.z, withSun = false)
            val basis = doubleArrayOf(
                0.282095,
                0.488603 * d.y, 0.488603 * d.z, 0.488603 * d.x,
                1.092548 * d.x * d.y, 1.092548 * d.y * d.z, 0.315392 * (3 * d.z * d.z - 1),
                1.092548 * d.x * d.z, 0.546274 * (d.x * d.x - d.y * d.y),
            )
            for (i in 0 until 9) {
                sh[i * 3] += c.r * basis[i] * w
                sh[i * 3 + 1] += c.g * basis[i] * w
                sh[i * 3 + 2] += c.b * basis[i] * w
            }
            weightSum += w
        }
        val norm = 4 * PI / weightSum
        // Lambertian band factors A_l / pi, and the basis constants again for the shader's polynomial form.
        val a = doubleArrayOf(1.0, 2.0 / 3, 2.0 / 3, 2.0 / 3, 0.25, 0.25, 0.25, 0.25, 0.25)
        val k = doubleArrayOf(0.282095, 0.488603, 0.488603, 0.488603, 1.092548, 1.092548, 0.315392, 1.092548, 0.546274)
        return FloatArray(27) { j -> (sh[j] * norm * a[j / 3] * k[j / 3]).toFloat() }
    }

    /** Fog color seen at the horizon (linear). */
    val fogColor get() = HORIZON
}
