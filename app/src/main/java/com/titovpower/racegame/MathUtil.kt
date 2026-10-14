package com.titovpower.racegame

import kotlin.math.floor
import kotlin.math.sqrt

/** Small immutable 3D vector used while building geometry. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): V3 {
        val l = length()
        return if (l < 1e-6f) V3(0f, 1f, 0f) else V3(x / l, y / l, z / l)
    }
}

/** RGB color in 0..1. */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    fun mix(o: Rgb, t: Float) = Rgb(r + (o.r - r) * t, g + (o.g - g) * t, b + (o.b - b) * t)
    fun scale(s: Float) = Rgb(r * s, g * s, b * s)

    companion object {
        fun hex(v: Int) = Rgb(((v shr 16) and 0xFF) / 255f, ((v shr 8) and 0xFF) / 255f, (v and 0xFF) / 255f)
    }
}

fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v

/** Deterministic 2D value noise in roughly 0..1, used for grass color and hills. */
object Noise {
    private fun hash(ix: Int, iz: Int): Float {
        var h = ix * 374761393 + iz * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    fun value(x: Float, z: Float): Float {
        val ix = floor(x).toInt()
        val iz = floor(z).toInt()
        val fx = smooth(x - ix)
        val fz = smooth(z - iz)
        val a = hash(ix, iz)
        val b = hash(ix + 1, iz)
        val c = hash(ix, iz + 1)
        val d = hash(ix + 1, iz + 1)
        val ab = a + (b - a) * fx
        val cd = c + (d - c) * fx
        return ab + (cd - ab) * fz
    }

    /** Two octaves of value noise. */
    fun fbm(x: Float, z: Float) = value(x, z) * 0.65f + value(x * 2.3f + 17f, z * 2.3f - 9f) * 0.35f
}
