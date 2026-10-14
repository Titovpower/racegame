package com.titovpower.racegame

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Accumulates triangles as interleaved vertex data:
 * position (3), normal (3), color (3), gloss (1) = [FLOATS_PER_VERTEX] floats per vertex.
 *
 * Gloss 0 is matte; higher values add specular highlights and sky reflections.
 * For unlit meshes (shadows) the gloss channel is used as opacity instead.
 *
 * Flat faces take an `inside` reference point; the face normal is flipped so it
 * points away from it. That keeps lighting right without caring about winding order.
 */
class MeshBuilder(initialCapacity: Int = 4096) {
    private var data = FloatArray(initialCapacity)
    var size = 0
        private set

    /** Gloss (or opacity, for unlit meshes) applied to vertices added from now on. */
    var gloss = 0f

    val vertexCount get() = size / FLOATS_PER_VERTEX

    fun toArray(): FloatArray = data.copyOf(size)

    private fun put(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun vertex(p: V3, n: V3, c: Rgb, g: Float = gloss) {
        put(p.x); put(p.y); put(p.z)
        put(n.x); put(n.y); put(n.z)
        put(c.r); put(c.g); put(c.b)
        put(g)
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
        orientedBox(V3(cx, cy, cz), V3(hx, 0f, 0f), V3(0f, hy, 0f), V3(0f, 0f, hz), color, topColor)
    }

    /** Box with arbitrary half-axis vectors u (x-ish), v (up-ish), w (z-ish). */
    fun orientedBox(c: V3, u: V3, v: V3, w: V3, color: Rgb, topColor: Rgb = color) {
        hexahedron(
            arrayOf(
                c - u - v - w, c + u - v - w, c + u - v + w, c - u - v + w,
                c - u + v - w, c + u + v - w, c + u + v + w, c - u + v + w,
            ),
            color, topColor,
        )
    }

    /** Vertical cone with smooth shading (tree foliage). */
    fun cone(cx: Float, baseY: Float, cz: Float, radius: Float, height: Float, segments: Int, color: Rgb, rot: Float = 0f) {
        val apex = V3(cx, baseY + height, cz)
        val slope = radius / height
        for (i in 0 until segments) {
            val a0 = rot + (i * 2.0 * PI / segments).toFloat()
            val a1 = rot + ((i + 1) * 2.0 * PI / segments).toFloat()
            val am = (a0 + a1) * 0.5f
            val p0 = V3(cx + cos(a0) * radius, baseY, cz + sin(a0) * radius)
            val p1 = V3(cx + cos(a1) * radius, baseY, cz + sin(a1) * radius)
            val n0 = V3(cos(a0), slope, sin(a0)).normalized()
            val n1 = V3(cos(a1), slope, sin(a1)).normalized()
            val nm = V3(cos(am), slope, sin(am)).normalized()
            // Lower rim slightly darker: fake self-shadowing under the branches.
            vertex(apex, nm, color.scale(1.12f))
            vertex(p0, n0, color.scale(0.78f))
            vertex(p1, n1, color.scale(0.78f))
            // Underside so the cone isn't hollow when seen from below.
            val down = V3(0f, -1f, 0f)
            val center = V3(cx, baseY, cz)
            vertex(center, down, color.scale(0.5f)); vertex(p1, down, color.scale(0.5f)); vertex(p0, down, color.scale(0.5f))
        }
    }

    /** Vertical prism without caps (tree trunks, posts), smooth shaded. */
    fun prism(cx: Float, y0: Float, cz: Float, radius: Float, height: Float, segments: Int, color: Rgb, topRadius: Float = radius) {
        for (i in 0 until segments) {
            val a0 = (i * 2.0 * PI / segments).toFloat()
            val a1 = ((i + 1) * 2.0 * PI / segments).toFloat()
            val n0 = V3(cos(a0), 0f, sin(a0))
            val n1 = V3(cos(a1), 0f, sin(a1))
            val b0 = V3(cx + n0.x * radius, y0, cz + n0.z * radius)
            val b1 = V3(cx + n1.x * radius, y0, cz + n1.z * radius)
            val t0 = V3(cx + n0.x * topRadius, y0 + height, cz + n0.z * topRadius)
            val t1 = V3(cx + n1.x * topRadius, y0 + height, cz + n1.z * topRadius)
            vertex(b0, n0, color); vertex(b1, n1, color); vertex(t1, n1, color)
            vertex(b0, n0, color); vertex(t1, n1, color); vertex(t0, n0, color)
        }
    }

    /** Smooth low-poly ellipsoid (tree crowns, bushes). */
    fun blob(c: V3, rx: Float, ry: Float, rz: Float, rings: Int, segments: Int, color: Rgb, jitterSeed: Int = 0) {
        fun point(ring: Int, seg: Int): Pair<V3, V3> {
            val phi = (ring.toFloat() / rings * PI).toFloat()
            val theta = (seg.toFloat() / segments * 2 * PI).toFloat()
            val n = V3(sin(phi) * cos(theta), cos(phi), sin(phi) * sin(theta))
            // Deterministic bumpiness so blobs don't look like perfect spheres.
            val j = if (ring == 0 || ring == rings) 1f else 0.85f + 0.3f * Noise.value(seg * 1.7f + jitterSeed, ring * 2.3f)
            return V3(c.x + n.x * rx * j, c.y + n.y * ry * j, c.z + n.z * rz * j) to n
        }
        for (r in 0 until rings) for (s in 0 until segments) {
            val (p00, n00) = point(r, s)
            val (p01, n01) = point(r, s + 1)
            val (p10, n10) = point(r + 1, s)
            val (p11, n11) = point(r + 1, s + 1)
            // Darker underneath, lighter on top.
            val c0 = color.scale(0.75f + 0.4f * (n00.y * 0.5f + 0.5f))
            val c1 = color.scale(0.75f + 0.4f * (n10.y * 0.5f + 0.5f))
            vertex(p00, n00, c0); vertex(p10, n10, c1); vertex(p11, n11, c1)
            vertex(p00, n00, c0); vertex(p11, n11, c1); vertex(p01, n01, c0)
        }
    }

    /**
     * Smooth surface through a grid of points: rings[i][j] is point j of cross-section i.
     * Rings are closed loops. Normals come from the grid's neighbors and point away from
     * each ring's center. Optional caps close the first and last ring.
     */
    fun loft(rings: List<List<V3>>, colorAt: (Int, Int) -> Rgb, glossAt: (Int, Int) -> Float, capStart: Boolean, capEnd: Boolean) {
        val nr = rings.size
        val ns = rings[0].size
        val centers = rings.map { ring ->
            var s = V3(0f, 0f, 0f)
            for (p in ring) s = s + p
            s * (1f / ring.size)
        }
        val normals = Array(nr) { i ->
            Array(ns) { j ->
                val along = rings[minOf(i + 1, nr - 1)][j] - rings[maxOf(i - 1, 0)][j]
                val around = rings[i][(j + 1) % ns] - rings[i][(j - 1 + ns) % ns]
                along.cross(around).normalized()
            }
        }
        // Rings may be concave (e.g. wheel arches), so orient all normals together:
        // flip everything if most of them point inwards.
        var outward = 0f
        for (i in 0 until nr) for (j in 0 until ns) outward += normals[i][j].dot(rings[i][j] - centers[i])
        if (outward < 0f) for (i in 0 until nr) for (j in 0 until ns) normals[i][j] = normals[i][j] * -1f
        for (i in 0 until nr - 1) for (j in 0 until ns) {
            val j1 = (j + 1) % ns
            val idx = arrayOf(i to j, i + 1 to j, i + 1 to j1, i to j, i + 1 to j1, i to j1)
            for ((a, b) in idx) vertex(rings[a][b], normals[a][b], colorAt(a, b), glossAt(a, b))
        }
        fun cap(i: Int, outward: V3) {
            for ((a, b, c) in triangulate(rings[i], outward)) {
                vertex(rings[i][a], outward, colorAt(i, a), glossAt(i, a))
                vertex(rings[i][b], outward, colorAt(i, b), glossAt(i, b))
                vertex(rings[i][c], outward, colorAt(i, c), glossAt(i, c))
            }
        }
        if (capStart) cap(0, (centers[0] - centers[1]).normalized())
        if (capEnd) cap(nr - 1, (centers[nr - 1] - centers[nr - 2]).normalized())
    }

    /** Ear-clipping triangulation of a planar simple polygon (may be concave). */
    private fun triangulate(poly: List<V3>, normal: V3): List<Triple<Int, Int, Int>> {
        // Project onto the plane most perpendicular to the normal.
        val ax = kotlin.math.abs(normal.x); val ay = kotlin.math.abs(normal.y); val az = kotlin.math.abs(normal.z)
        val pts = poly.map {
            when {
                ax >= ay && ax >= az -> floatArrayOf(it.z, it.y)
                ay >= az -> floatArrayOf(it.x, it.z)
                else -> floatArrayOf(it.x, it.y)
            }
        }
        val idx = poly.indices.toMutableList()
        var area = 0f
        for (k in pts.indices) {
            val p = pts[k]; val q = pts[(k + 1) % pts.size]
            area += p[0] * q[1] - q[0] * p[1]
        }
        val ccw = area > 0f
        fun cross(o: FloatArray, a: FloatArray, b: FloatArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        fun inside(p: FloatArray, a: FloatArray, b: FloatArray, c: FloatArray): Boolean {
            val d1 = cross(a, b, p); val d2 = cross(b, c, p); val d3 = cross(c, a, p)
            return if (ccw) d1 >= 0f && d2 >= 0f && d3 >= 0f else d1 <= 0f && d2 <= 0f && d3 <= 0f
        }
        val out = ArrayList<Triple<Int, Int, Int>>()
        var guard = 0
        while (idx.size > 3 && guard++ < 10000) {
            var clipped = false
            for (k in idx.indices) {
                val ia = idx[(k - 1 + idx.size) % idx.size]; val ib = idx[k]; val ic = idx[(k + 1) % idx.size]
                val a = pts[ia]; val b = pts[ib]; val c = pts[ic]
                val convex = if (ccw) cross(a, b, c) > 0f else cross(a, b, c) < 0f
                if (!convex) continue
                if (idx.any { it != ia && it != ib && it != ic && inside(pts[it], a, b, c) }) continue
                out.add(Triple(ia, ib, ic))
                idx.removeAt(k)
                clipped = true
                break
            }
            if (!clipped) break
        }
        if (idx.size == 3) out.add(Triple(idx[0], idx[1], idx[2]))
        return out
    }

    /** Flat disc on the ground whose opacity fades to zero at the rim (soft shadows). */
    fun softDisc(cx: Float, y: Float, cz: Float, rx: Float, rz: Float, segments: Int, opacity: Float) {
        val up = V3(0f, 1f, 0f)
        val black = Rgb(0f, 0f, 0f)
        val center = V3(cx, y, cz)
        for (i in 0 until segments) {
            val a0 = (i * 2.0 * PI / segments).toFloat()
            val a1 = ((i + 1) * 2.0 * PI / segments).toFloat()
            val i0 = V3(cx + cos(a0) * rx * 0.55f, y, cz + sin(a0) * rz * 0.55f)
            val i1 = V3(cx + cos(a1) * rx * 0.55f, y, cz + sin(a1) * rz * 0.55f)
            val o0 = V3(cx + cos(a0) * rx, y, cz + sin(a0) * rz)
            val o1 = V3(cx + cos(a1) * rx, y, cz + sin(a1) * rz)
            vertex(center, up, black, opacity); vertex(i0, up, black, opacity); vertex(i1, up, black, opacity)
            vertex(i0, up, black, opacity); vertex(o0, up, black, 0f); vertex(o1, up, black, 0f)
            vertex(i0, up, black, opacity); vertex(o1, up, black, 0f); vertex(i1, up, black, opacity)
        }
    }

    companion object {
        const val FLOATS_PER_VERTEX = 10
    }
}
