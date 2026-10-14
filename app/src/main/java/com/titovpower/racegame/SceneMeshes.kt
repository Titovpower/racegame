package com.titovpower.racegame

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Builds all procedural geometry for the scene (CPU side only). */
object SceneMeshes {
    private val GRASS_A = Rgb.hex(0x4C8A2E)
    private val GRASS_B = Rgb.hex(0x6FA83A)
    private val GRASS_DRY = Rgb.hex(0x8E9A4A)
    private val ROCK = Rgb.hex(0x7D7A73)
    private val ASPHALT = Rgb.hex(0x45464A)
    private val CURB_RED = Rgb.hex(0xC62828)
    private val CURB_WHITE = Rgb.hex(0xEEEEEE)
    private val LINE = Rgb.hex(0xF2F2F2)
    private val TRUNK = Rgb.hex(0x6B4A2B)
    private val PINE = Rgb.hex(0x2F6B34)
    private val LEAF = Rgb.hex(0x4E8F2F)

    val SKY_ZENITH = Rgb.hex(0x3C78C8)
    val SKY_HORIZON = Rgb.hex(0xBFD8EC)

    fun ground(world: World): FloatArray {
        val b = MeshBuilder(1 shl 20)
        val ext = world.groundExtent
        val cells = 160
        val step = 2f * ext / cells
        val n = cells + 1
        val hs = FloatArray(n * n)
        for (iz in 0 until n) for (ix in 0 until n) {
            hs[iz * n + ix] = world.heightAt(-ext + ix * step, -ext + iz * step)
        }
        fun p(ix: Int, iz: Int) = V3(-ext + ix * step, hs[iz * n + ix], -ext + iz * step)
        for (iz in 0 until cells) for (ix in 0 until cells) {
            val a = p(ix, iz); val bb = p(ix + 1, iz); val c = p(ix + 1, iz + 1); val d = p(ix, iz + 1)
            val cx = a.x + step * 0.5f
            val cz = a.z + step * 0.5f
            val noise = Noise.fbm(cx * 0.03f, cz * 0.03f)
            var color = GRASS_A.mix(GRASS_B, noise).mix(GRASS_DRY, clamp(Noise.value(cx * 0.01f, cz * 0.01f) - 0.55f, 0f, 1f) * 1.5f)
            val h = (a.y + c.y) * 0.5f
            if (h > 45f) color = color.mix(ROCK, clamp((h - 45f) / 40f, 0f, 0.85f))
            if (h > 110f) color = color.mix(Rgb(0.95f, 0.96f, 0.98f), clamp((h - 110f) / 30f, 0f, 1f))
            // Two triangles with independent normals so hills get faceted lighting.
            val below = (a + c) * 0.5f - V3(0f, 1000f, 0f)
            b.tri(a, bb, c, color, below)
            b.tri(a, c, d, color.scale(0.96f), below)
        }
        return b.toArray()
    }

    fun road(track: Track): FloatArray {
        val b = MeshBuilder(1 shl 16)
        val n = track.samples
        val hw = track.halfWidth
        val cw = track.curbWidth
        fun edge(i: Int, offset: Float, y: Float): V3 {
            val t = track.tangent(i)
            // Left of travel direction.
            val nx = t.z
            val nz = -t.x
            return V3(track.xs[i] + nx * offset, y, track.zs[i] + nz * offset)
        }
        for (i in 0 until n) {
            val j = (i + 1) % n
            b.flatQuad(edge(i, -hw, 0.02f), edge(i, hw, 0.02f), edge(j, hw, 0.02f), edge(j, -hw, 0.02f), ASPHALT)
            val curb = if ((i / 3) % 2 == 0) CURB_RED else CURB_WHITE
            b.flatQuad(edge(i, hw, 0.04f), edge(i, hw + cw, 0.04f), edge(j, hw + cw, 0.04f), edge(j, hw, 0.04f), curb)
            b.flatQuad(edge(i, -hw - cw, 0.04f), edge(i, -hw, 0.04f), edge(j, -hw, 0.04f), edge(j, -hw - cw, 0.04f), curb)
            // Edge lines and a dashed center line.
            b.flatQuad(edge(i, hw - 0.5f, 0.05f), edge(i, hw - 0.3f, 0.05f), edge(j, hw - 0.3f, 0.05f), edge(j, hw - 0.5f, 0.05f), LINE)
            b.flatQuad(edge(i, -hw + 0.3f, 0.05f), edge(i, -hw + 0.5f, 0.05f), edge(j, -hw + 0.5f, 0.05f), edge(j, -hw + 0.3f, 0.05f), LINE)
            if ((i / 2) % 3 == 0) {
                b.flatQuad(edge(i, -0.15f, 0.05f), edge(i, 0.15f, 0.05f), edge(j, 0.15f, 0.05f), edge(j, -0.15f, 0.05f), LINE)
            }
        }
        // Checkered start/finish line at sample 0.
        val rows = 2
        val cols = 14
        val t = track.tangent(0)
        val left = V3(t.z, 0f, -t.x)
        val origin = V3(track.xs[0], 0.06f, track.zs[0])
        val cell = 2f * hw / cols
        for (r in 0 until rows) for (c in 0 until cols) {
            val o = origin + left * (-hw + c * cell) + t * (r * cell)
            val color = if ((r + c) % 2 == 0) Rgb(0.05f, 0.05f, 0.05f) else Rgb(0.97f, 0.97f, 0.97f)
            b.flatQuad(o, o + left * cell, o + left * cell + t * cell, o + t * cell, color)
        }
        return b.toArray()
    }

    /** Start/finish gantry spanning the track. */
    fun gantry(track: Track): FloatArray {
        val b = MeshBuilder()
        val t = track.tangent(0)
        val left = V3(t.z, 0f, -t.x)
        val origin = V3(track.xs[0], 0f, track.zs[0])
        val span = track.halfWidth + track.curbWidth + 1.5f
        val grey = Rgb.hex(0x9E9E9E)
        for (side in floatArrayOf(-span, span)) {
            val p = origin + left * side
            b.box(p.x, 3.2f, p.z, 0.35f, 3.2f, 0.35f, grey)
        }
        // Beam: a hexahedron oriented along the "left" axis.
        val a = origin + left * (-span - 0.4f)
        val c = origin + left * (span + 0.4f)
        val fw = t * 0.5f
        val y0 = 6.0f
        val y1 = 7.4f
        fun at(p: V3, y: Float) = V3(p.x, y, p.z)
        b.hexahedron(
            arrayOf(
                at(a - fw, y0), at(c - fw, y0), at(c + fw, y0), at(a + fw, y0),
                at(a - fw, y1), at(c - fw, y1), at(c + fw, y1), at(a + fw, y1),
            ),
            Rgb.hex(0xD32F2F),
        )
        return b.toArray()
    }

    fun vegetation(world: World): FloatArray {
        val b = MeshBuilder(1 shl 19)
        for (tr in world.trees) {
            val s = tr.scale
            val rot = (tr.x * 13.1f + tr.z * 7.7f) % 6.28f
            val tint = 0.85f + Noise.value(tr.x * 0.3f, tr.z * 0.3f) * 0.3f
            if (tr.pine) {
                b.prism(tr.x, tr.y - 0.5f, tr.z, 0.35f * s, 2.5f * s, 5, TRUNK)
                b.cone(tr.x, tr.y + 1.6f * s, tr.z, 2.6f * s, 4.5f * s, 7, PINE.scale(tint), rot)
                b.cone(tr.x, tr.y + 3.8f * s, tr.z, 1.9f * s, 3.6f * s, 7, PINE.scale(tint * 1.08f), rot + 0.4f)
            } else {
                b.prism(tr.x, tr.y - 0.5f, tr.z, 0.4f * s, 3.4f * s, 5, TRUNK)
                // Broadleaf crown: a squashed octahedron.
                val cy = tr.y + 4.6f * s
                val r = 2.6f * s
                val top = V3(tr.x, cy + 2.2f * s, tr.z)
                val bottom = V3(tr.x, cy - 1.6f * s, tr.z)
                val center = V3(tr.x, cy, tr.z)
                val leaf = LEAF.scale(tint)
                val ring = Array(6) { k ->
                    val a = rot + (k * PI / 3).toFloat()
                    V3(tr.x + cos(a) * r, cy, tr.z + sin(a) * r)
                }
                for (k in 0 until 6) {
                    val p0 = ring[k]
                    val p1 = ring[(k + 1) % 6]
                    b.tri(top, p0, p1, leaf.scale(if (k % 2 == 0) 1.05f else 0.95f), center)
                    b.tri(bottom, p0, p1, leaf.scale(0.8f), center)
                }
            }
        }
        for (r in world.rocks) {
            val s = r.size
            val j = Noise.value(r.x, r.z)
            b.hexahedron(
                arrayOf(
                    V3(r.x - s, r.y - 0.3f, r.z - s * 0.8f), V3(r.x + s * 0.9f, r.y - 0.3f, r.z - s),
                    V3(r.x + s, r.y - 0.3f, r.z + s * 0.7f), V3(r.x - s * 0.8f, r.y - 0.3f, r.z + s),
                    V3(r.x - s * 0.5f, r.y + s * (0.6f + j), r.z - s * 0.4f), V3(r.x + s * 0.4f, r.y + s * 0.8f, r.z - s * 0.6f),
                    V3(r.x + s * 0.6f, r.y + s * 0.5f, r.z + s * 0.3f), V3(r.x - s * 0.3f, r.y + s * 0.7f, r.z + s * 0.5f),
                ),
                ROCK.scale(0.8f + j * 0.4f),
            )
        }
        return b.toArray()
    }

    /** Sports car body; local +z is forward, origin on the ground at the car's center. */
    fun carBody(): FloatArray {
        val b = MeshBuilder()
        val paint = Rgb.hex(0xD50000)
        val paintDark = Rgb.hex(0x9A0007)
        val glass = Rgb.hex(0x1B2631)
        val black = Rgb.hex(0x151515)
        // Lower body: wide and low, with a tapered nose.
        b.hexahedron(
            arrayOf(
                V3(-0.95f, 0.22f, -2.15f), V3(0.95f, 0.22f, -2.15f), V3(0.9f, 0.25f, 2.25f), V3(-0.9f, 0.25f, 2.25f),
                V3(-0.95f, 0.78f, -2.1f), V3(0.95f, 0.78f, -2.1f), V3(0.82f, 0.55f, 2.2f), V3(-0.82f, 0.55f, 2.2f),
            ),
            paint,
        )
        // Cabin with raked windshield and fastback.
        b.hexahedron(
            arrayOf(
                V3(-0.82f, 0.76f, -1.45f), V3(0.82f, 0.76f, -1.45f), V3(0.82f, 0.72f, 0.75f), V3(-0.82f, 0.72f, 0.75f),
                V3(-0.62f, 1.2f, -0.75f), V3(0.62f, 1.2f, -0.75f), V3(0.62f, 1.2f, -0.05f), V3(-0.62f, 1.2f, -0.05f),
            ),
            glass, paint,
        )
        // Side skirts.
        b.box(0.97f, 0.3f, 0f, 0.05f, 0.08f, 1.5f, black)
        b.box(-0.97f, 0.3f, 0f, 0.05f, 0.08f, 1.5f, black)
        // Rear spoiler.
        b.box(0.55f, 0.92f, -1.95f, 0.05f, 0.14f, 0.08f, black)
        b.box(-0.55f, 0.92f, -1.95f, 0.05f, 0.14f, 0.08f, black)
        b.box(0f, 1.08f, -2.0f, 0.95f, 0.04f, 0.2f, paintDark)
        // Lights.
        b.box(0.62f, 0.5f, 2.2f, 0.2f, 0.06f, 0.06f, Rgb(1f, 0.98f, 0.85f))
        b.box(-0.62f, 0.5f, 2.2f, 0.2f, 0.06f, 0.06f, Rgb(1f, 0.98f, 0.85f))
        b.box(0.65f, 0.64f, -2.16f, 0.22f, 0.06f, 0.04f, Rgb(1f, 0.1f, 0.1f))
        b.box(-0.65f, 0.64f, -2.16f, 0.22f, 0.06f, 0.04f, Rgb(1f, 0.1f, 0.1f))
        // Front grille and racing stripe.
        b.box(0f, 0.34f, 2.24f, 0.5f, 0.07f, 0.03f, black)
        b.hexahedron(
            arrayOf(
                V3(-0.18f, 0.79f, -2.11f), V3(0.18f, 0.79f, -2.11f), V3(0.18f, 0.56f, 2.21f), V3(-0.18f, 0.56f, 2.21f),
                V3(-0.18f, 0.80f, -2.11f), V3(0.18f, 0.80f, -2.11f), V3(0.18f, 0.57f, 2.21f), V3(-0.18f, 0.57f, 2.21f),
            ),
            Rgb(0.95f, 0.95f, 0.95f),
        )
        return b.toArray()
    }

    fun wheel(): FloatArray {
        val b = MeshBuilder()
        b.wheel(CarPhysics.WHEEL_RADIUS, 0.14f, 14, Rgb.hex(0x1C1C1C), Rgb.hex(0xB0B4B8))
        return b.toArray()
    }

    /** Soft dark quad under the car (drawn with blending). */
    fun shadow(): FloatArray {
        val b = MeshBuilder()
        val c = Rgb(0f, 0f, 0f)
        b.flatQuad(V3(-1.15f, 0.08f, -2.4f), V3(1.15f, 0.08f, -2.4f), V3(1.15f, 0.08f, 2.45f), V3(-1.15f, 0.08f, 2.45f), c)
        return b.toArray()
    }

    /** Sky dome around the origin; translated with the camera when drawn. */
    fun sky(): FloatArray {
        val b = MeshBuilder()
        val radius = 1000f
        val rings = 8
        val segs = 24
        val center = V3(0f, 0f, 0f)
        fun pt(ring: Int, seg: Int): V3 {
            val elev = (ring.toFloat() / rings) * (PI / 2).toFloat() - 0.15f
            val az = (seg.toFloat() / segs) * (2 * PI).toFloat()
            return V3(cos(elev) * cos(az) * radius, sin(elev) * radius, cos(elev) * sin(az) * radius)
        }
        for (r in 0 until rings) for (s in 0 until segs) {
            val t = clamp((r + 0.5f) / rings, 0f, 1f)
            val color = SKY_HORIZON.mix(SKY_ZENITH, t * t * 0.9f + t * 0.1f)
            b.quad(pt(r, s), pt(r, s + 1), pt(r + 1, s + 1), pt(r + 1, s), color, center)
        }
        return b.toArray()
    }
}
