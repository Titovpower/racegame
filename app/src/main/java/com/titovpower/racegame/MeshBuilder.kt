package com.titovpower.racegame

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Accumulates flat-shaded triangles as interleaved vertex data:
 * position (3), normal (3), color (3) = [FLOATS_PER_VERTEX] floats per vertex.
 *
 * Every face takes an `inside` reference point; the face normal is flipped so it
 * points away from it. That keeps lighting right without caring about winding order.
 */
class MeshBuilder(initialCapacity: Int = 4096) {
    private var data = FloatArray(initialCapacity)
    var size = 0
        private set

    val vertexCount get() = size / FLOATS_PER_VERTEX

    fun toArray(): FloatArray = data.copyOf(size)

    private fun put(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    private fun vertex(p: V3, n: V3, c: Rgb) {
        put(p.x); put(p.y); put(p.z)
        put(n.x); put(n.y); put(n.z)
        put(c.r); put(c.g); put(c.b)
    }

    fun tri(a: V3, b: V3, c: V3, color: Rgb, inside: V3) {
        var n = (b - a).cross(c - a).normalized()
        val center = (a + b + c) * (1f / 3f)
        if (n.dot(center - inside) < 0f) n = n * -1f
        vertex(a, n, color); vertex(b, n, color); vertex(c, n, color)
    }

    fun quad(a: V3, b: V3, c: V3, d: V3, color: Rgb, inside: V3) {
        tri(a, b, c, color, inside)
        tri(a, c, d, color, inside)
    }

    /** Quad lying on (or just above) the ground, lit from above. */
    fun flatQuad(a: V3, b: V3, c: V3, d: V3, color: Rgb) {
        val below = (a + c) * 0.5f - V3(0f, 100f, 0f)
        quad(a, b, c, d, color, below)
    }

    /**
     * Six-sided solid from 8 corners: bottom 0..3 then top 4..7, both in the
     * order (-x,-z), (+x,-z), (+x,+z), (-x,+z).
     */
    fun hexahedron(c: Array<V3>, color: Rgb, topColor: Rgb = color) {
        var inside = V3(0f, 0f, 0f)
        for (p in c) inside = inside + p
        inside = inside * (1f / 8f)
        quad(c[0], c[1], c[2], c[3], color, inside)
        quad(c[4], c[5], c[6], c[7], topColor, inside)
        quad(c[0], c[1], c[5], c[4], color, inside)
        quad(c[1], c[2], c[6], c[5], color, inside)
        quad(c[2], c[3], c[7], c[6], color, inside)
        quad(c[3], c[0], c[4], c[7], color, inside)
    }

    /** Axis-aligned box from center and half extents. */
    fun box(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, color: Rgb, topColor: Rgb = color) {
        hexahedron(
            arrayOf(
                V3(cx - hx, cy - hy, cz - hz), V3(cx + hx, cy - hy, cz - hz),
                V3(cx + hx, cy - hy, cz + hz), V3(cx - hx, cy - hy, cz + hz),
                V3(cx - hx, cy + hy, cz - hz), V3(cx + hx, cy + hy, cz - hz),
                V3(cx + hx, cy + hy, cz + hz), V3(cx - hx, cy + hy, cz + hz),
            ),
            color, topColor,
        )
    }

    /** Vertical cone (tree foliage, mountains). */
    fun cone(cx: Float, baseY: Float, cz: Float, radius: Float, height: Float, segments: Int, color: Rgb, rot: Float = 0f) {
        val apex = V3(cx, baseY + height, cz)
        val inside = V3(cx, baseY + height * 0.25f, cz)
        for (i in 0 until segments) {
            val a0 = rot + (i * 2.0 * PI / segments).toFloat()
            val a1 = rot + ((i + 1) * 2.0 * PI / segments).toFloat()
            val p0 = V3(cx + cos(a0) * radius, baseY, cz + sin(a0) * radius)
            val p1 = V3(cx + cos(a1) * radius, baseY, cz + sin(a1) * radius)
            // Slight per-face shade variation makes low-poly cones read better.
            val shade = 0.9f + 0.2f * ((i * 7919) % 5) / 4f
            tri(apex, p0, p1, color.scale(shade), inside)
        }
    }

    /** Vertical prism without caps (tree trunks, posts). */
    fun prism(cx: Float, y0: Float, cz: Float, radius: Float, height: Float, segments: Int, color: Rgb) {
        val inside = V3(cx, y0 + height * 0.5f, cz)
        for (i in 0 until segments) {
            val a0 = (i * 2.0 * PI / segments).toFloat()
            val a1 = ((i + 1) * 2.0 * PI / segments).toFloat()
            val b0 = V3(cx + cos(a0) * radius, y0, cz + sin(a0) * radius)
            val b1 = V3(cx + cos(a1) * radius, y0, cz + sin(a1) * radius)
            quad(b0, b1, b1 + V3(0f, height, 0f), b0 + V3(0f, height, 0f), color, inside)
        }
    }

    /** Cylinder whose axis is the x axis, centered at origin (car wheel). */
    fun wheel(radius: Float, halfWidth: Float, segments: Int, tire: Rgb, rim: Rgb) {
        val inside = V3(0f, 0f, 0f)
        for (i in 0 until segments) {
            val a0 = (i * 2.0 * PI / segments).toFloat()
            val a1 = ((i + 1) * 2.0 * PI / segments).toFloat()
            val y0 = cos(a0) * radius; val z0 = sin(a0) * radius
            val y1 = cos(a1) * radius; val z1 = sin(a1) * radius
            quad(V3(-halfWidth, y0, z0), V3(halfWidth, y0, z0), V3(halfWidth, y1, z1), V3(-halfWidth, y1, z1), tire, inside)
            for (side in floatArrayOf(-halfWidth, halfWidth)) {
                val hub = V3(side, 0f, 0f)
                // Alternate rim colors so the spin is visible.
                val c = if (i % 2 == 0) rim else rim.scale(0.55f)
                tri(hub, V3(side, y0 * 0.7f, z0 * 0.7f), V3(side, y1 * 0.7f, z1 * 0.7f), c, inside)
                quad(
                    V3(side, y0 * 0.7f, z0 * 0.7f), V3(side, y0, z0), V3(side, y1, z1), V3(side, y1 * 0.7f, z1 * 0.7f),
                    tire, inside,
                )
            }
        }
    }

    companion object {
        const val FLOATS_PER_VERTEX = 9
    }
}
