package com.titovpower.racegame

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A closed race track: a wobbly loop around the origin, sampled as a polyline.
 * The world uses x/z for the ground plane and y for up.
 */
class Track(val samples: Int = 600) {
    val halfWidth = 7f
    val curbWidth = 1.2f

    val xs = FloatArray(samples)
    val zs = FloatArray(samples)

    init {
        for (i in 0 until samples) {
            val t = (i * 2.0 * PI / samples).toFloat()
            val r = 190f + 45f * sin(2f * t) + 28f * cos(3f * t + 0.6f) + 12f * sin(5f * t + 1.3f)
            xs[i] = cos(t) * r * 1.15f
            zs[i] = sin(t) * r * 0.85f
        }
    }

    /** Unit tangent (direction of travel) at sample i. */
    fun tangent(i: Int): V3 {
        val n = (i + 1) % samples
        val p = (i - 1 + samples) % samples
        return V3(xs[n] - xs[p], 0f, zs[n] - zs[p]).normalized()
    }

    /** Heading (yaw) to face along the track at sample i; forward = (sin yaw, cos yaw). */
    fun headingAt(i: Int): Float {
        val t = tangent(i)
        return atan2(t.x, t.z)
    }

    data class Nearest(val index: Int, val distance: Float)

    /** Closest point on the centerline polyline to (x, z). */
    fun nearest(x: Float, z: Float): Nearest {
        var best = Float.MAX_VALUE
        var bestIdx = 0
        for (i in 0 until samples) {
            val j = (i + 1) % samples
            val ax = xs[i]; val az = zs[i]
            val dx = xs[j] - ax; val dz = zs[j] - az
            val len2 = dx * dx + dz * dz
            var t = ((x - ax) * dx + (z - az) * dz) / len2
            t = clamp(t, 0f, 1f)
            val px = ax + dx * t - x
            val pz = az + dz * t - z
            val d2 = px * px + pz * pz
            if (d2 < best) {
                best = d2
                bestIdx = i
            }
        }
        return Nearest(bestIdx, sqrt(best))
    }

    /** True when (x, z) is on asphalt or curbs. */
    fun isOnRoad(x: Float, z: Float) = nearest(x, z).distance <= halfWidth + curbWidth

    fun length(): Float {
        var sum = 0f
        for (i in 0 until samples) {
            val j = (i + 1) % samples
            val dx = xs[j] - xs[i]; val dz = zs[j] - zs[i]
            sum += sqrt(dx * dx + dz * dz)
        }
        return sum
    }
}
