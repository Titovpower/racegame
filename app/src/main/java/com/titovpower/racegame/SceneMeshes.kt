package com.titovpower.racegame

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Builds the procedural geometry of the world (CPU side only). The car is in [CarModel]. */
object SceneMeshes {
    private val GRASS_MOWN = Rgb.hex(0x5B8F3A)
    private val GRASS_LUSH = Rgb.hex(0x4B7F2F)
    private val GRASS_WILD = Rgb.hex(0x748C3E)
    private val GRASS_DRY = Rgb.hex(0x9C9A55)
    private val ROCK = Rgb.hex(0x857F76)
    private val SNOW = Rgb.hex(0xF2F5F8)
    private val ASPHALT = Rgb.hex(0x3E3F43)
    private val CURB_RED = Rgb.hex(0xC62828)
    private val CURB_WHITE = Rgb.hex(0xEEEEEE)
    private val LINE = Rgb.hex(0xEDEDED)
    private val TRUNK = Rgb.hex(0x5D4128)
    private val PINE = Rgb.hex(0x2C5E2E)
    private val LEAF = Rgb.hex(0x4A8A2C)
    private val LEAF_AUTUMN = Rgb.hex(0x8E9A2A)
    private val BUSH = Rgb.hex(0x3F7A2A)

    fun ground(world: World): FloatArray {
        val b = MeshBuilder(1 shl 21)
        val ext = world.groundExtent
        val cells = 160
        val step = 2f * ext / cells
        val n = cells + 1
        val hs = FloatArray(n * n)
        val ds = FloatArray(n * n)
        for (iz in 0 until n) for (ix in 0 until n) {
            val x = -ext + ix * step
            val z = -ext + iz * step
            val d = world.track.nearest(x, z).distance
            ds[iz * n + ix] = d
            hs[iz * n + ix] = world.heightAt(x, z, d)
        }
        fun h(ix: Int, iz: Int) = hs[iz.coerceIn(0, cells) * n + ix.coerceIn(0, cells)]
        val pos = Array(n * n) { k -> V3(-ext + (k % n) * step, hs[k], -ext + (k / n) * step) }
        // Smooth normals from the height field.
        val nor = Array(n * n) { k ->
            val ix = k % n
            val iz = k / n
            V3(-(h(ix + 1, iz) - h(ix - 1, iz)) / (2 * step), 1f, -(h(ix, iz + 1) - h(ix, iz - 1)) / (2 * step)).normalized()
        }
        val col = Array(n * n) { k ->
            val p = pos[k]
            val d = ds[k]
            val noise = Noise.fbm(p.x * 0.03f, p.z * 0.03f)
            // Mown grass near the track, lusher and wilder further out.
            val near = clamp((d - 15f) / 40f, 0f, 1f)
            var c = GRASS_MOWN.mix(GRASS_LUSH, noise).mix(GRASS_WILD.mix(GRASS_LUSH, noise), near)
            c = c.mix(GRASS_DRY, clamp(Noise.value(p.x * 0.012f, p.z * 0.012f) - 0.5f, 0f, 1f) * 1.4f)
            val steep = 1f - nor[k].y
            if (p.y > 30f || steep > 0.25f) c = c.mix(ROCK, clamp(maxOf((p.y - 30f) / 50f, (steep - 0.25f) * 3f), 0f, 0.85f))
            if (p.y > 105f) c = c.mix(SNOW, clamp((p.y - 105f) / 25f, 0f, 1f))
            c
        }
        for (iz in 0 until cells) for (ix in 0 until cells) {
            val k00 = iz * n + ix
            val k10 = k00 + 1
            val k01 = k00 + n
            val k11 = k01 + 1
            for (k in intArrayOf(k00, k10, k11, k00, k11, k01)) b.vertex(pos[k], nor[k], col[k])
        }
        return b.toArray()
    }

    fun road(track: Track): FloatArray {
        val b = MeshBuilder(1 shl 17)
        val n = track.samples
        val hw = track.halfWidth
        val cw = track.curbWidth
        fun edge(i: Int, offset: Float, y: Float): V3 {
            val t = track.tangent(i)
            // Left of travel direction.
            return V3(track.xs[i] + t.z * offset, y, track.zs[i] - t.x * offset)
        }
        fun strip(i: Int, j: Int, from: Float, to: Float, y: Float, color: Rgb) =
            b.flatQuad(edge(i, from, y), edge(i, to, y), edge(j, to, y), edge(j, from, y), color)
        for (i in 0 until n) {
            val j = (i + 1) % n
            // Asphalt in lanes: slightly darker, rubbered-in racing line in the middle.
            strip(i, j, -hw, -hw * 0.4f, 0.02f, ASPHALT)
            strip(i, j, -hw * 0.4f, hw * 0.4f, 0.02f, ASPHALT.scale(0.9f))
            strip(i, j, hw * 0.4f, hw, 0.02f, ASPHALT)
            val curb = if ((i / 3) % 2 == 0) CURB_RED else CURB_WHITE
            strip(i, j, hw, hw + cw, 0.05f, curb)
            strip(i, j, -hw - cw, -hw, 0.05f, curb)
            // Edge lines and a dashed center line.
            strip(i, j, hw - 0.5f, hw - 0.3f, 0.04f, LINE)
            strip(i, j, -hw + 0.3f, -hw + 0.5f, 0.04f, LINE)
            if ((i / 2) % 3 == 0) strip(i, j, -0.15f, 0.15f, 0.04f, LINE)
        }
        // Checkered start/finish line at sample 0.
        val cols = 14
        val t = track.tangent(0)
        val left = V3(t.z, 0f, -t.x)
        val origin = V3(track.xs[0], 0.05f, track.zs[0])
        val cell = 2f * hw / cols
        for (r in 0 until 2) for (c in 0 until cols) {
            val o = origin + left * (-hw + c * cell) + t * (r * cell)
            val color = if ((r + c) % 2 == 0) Rgb(0.06f, 0.06f, 0.06f) else Rgb(0.95f, 0.95f, 0.95f)
            b.flatQuad(o, o + left * cell, o + left * cell + t * cell, o + t * cell, color)
        }
        return b.toArray()
    }

    /** Start gantry and grandstand. */
    fun props(world: World): FloatArray {
        val b = MeshBuilder(1 shl 16)
        val track = world.track
        val t = track.tangent(0)
        val left = V3(t.z, 0f, -t.x)
        val origin = V3(track.xs[0], 0f, track.zs[0])
        val span = track.halfWidth + track.curbWidth + 1.5f
        val steel = Rgb.hex(0x9EA3A8)
        b.gloss = 0.4f
        for (side in floatArrayOf(-span, span)) {
            val p = origin + left * side
            b.orientedBox(V3(p.x, 3.4f, p.z), left * 0.3f, V3(0f, 3.4f, 0f), t * 0.3f, steel)
        }
        val beamCenter = V3(origin.x, 7.0f, origin.z)
        b.gloss = 0.3f
        b.orientedBox(beamCenter, left * (span + 0.4f), V3(0f, 0.75f, 0f), t * 0.45f, Rgb.hex(0xD32F2F))
        // Checkered banner on the beam, both sides.
        val cells = 16
        for (c in 0 until cells) for (r in 0 until 2) {
            val color = if ((c + r) % 2 == 0) Rgb(0.05f, 0.05f, 0.05f) else Rgb(0.95f, 0.95f, 0.95f)
            val u = -span + (c + 0.5f) * (2 * span / cells)
            for (face in floatArrayOf(-0.47f, 0.47f)) {
                val p = beamCenter + left * u + t * face + V3(0f, -0.35f + r * 0.7f, 0f)
                b.orientedBox(p, left * (span / cells), V3(0f, 0.35f, 0f), t * 0.01f, color)
            }
        }

        // Grandstand: stepped concrete tiers with colorful seats under a roof.
        b.gloss = 0f
        val (gx, gz) = world.grandstandCenter()
        val gt = track.tangent(world.grandstandIndex)
        val gl = V3(gt.z, 0f, -gt.x)
        val front = V3(gx, 0f, gz)
        val half = world.grandstandLength / 2f
        val depth = world.grandstandDepth
        val tiers = 6
        val concrete = Rgb.hex(0xB9B4AA)
        val seatColors = listOf(Rgb.hex(0x1E88E5), Rgb.hex(0xFDD835), Rgb.hex(0xE53935), Rgb.hex(0xFAFAFA), Rgb.hex(0x43A047))
        val rnd = Random(3)
        for (k in 0 until tiers) {
            val d = (k + 0.5f) * depth / tiers
            val hgt = (k + 1) * 0.75f
            val c = front + gl * d + V3(0f, hgt / 2f, 0f)
            b.orientedBox(c, gt * half, V3(0f, hgt / 2f, 0f), gl * (depth / tiers / 2f), concrete)
            // Seats (spectators from afar).
            var s = -half + 0.5f
            while (s < half - 0.4f) {
                val seat = front + gl * (d - 0.2f) + gt * s + V3(0f, hgt + 0.25f, 0f)
                b.orientedBox(seat, gt * 0.22f, V3(0f, 0.25f, 0f), gl * 0.18f, seatColors[rnd.nextInt(seatColors.size)])
                s += 0.62f
            }
        }
        // Back wall, posts and a sloped roof.
        val wallC = front + gl * (depth + 0.15f) + V3(0f, 3.6f, 0f)
        b.orientedBox(wallC, gt * half, V3(0f, 3.6f, 0f), gl * 0.15f, concrete.scale(0.9f))
        b.gloss = 0.3f
        for (k in 0..4) {
            val p = front + gt * (-half + 1f + k * (2 * half - 2f) / 4f) + gl * 0.4f
            b.orientedBox(p + V3(0f, 3.6f, 0f), gt * 0.12f, V3(0f, 3.6f, 0f), gl * 0.12f, steel)
        }
        val roofFront = front + V3(0f, 7.2f, 0f) + gl * -0.6f
        val roofBack = front + V3(0f, 7.9f, 0f) + gl * (depth + 0.4f)
        b.hexahedron(
            arrayOf(
                roofFront - gt * (half + 1f), roofFront + gt * (half + 1f), roofBack + gt * (half + 1f), roofBack - gt * (half + 1f),
                roofFront - gt * (half + 1f) + V3(0f, 0.25f, 0f), roofFront + gt * (half + 1f) + V3(0f, 0.25f, 0f),
                roofBack + gt * (half + 1f) + V3(0f, 0.25f, 0f), roofBack - gt * (half + 1f) + V3(0f, 0.25f, 0f),
            ),
            Rgb.hex(0xECEFF1),
        )
        b.gloss = 0f
        return b.toArray()
    }

    fun vegetation(world: World): FloatArray {
        val b = MeshBuilder(1 shl 21)
        for (tr in world.trees) {
            val s = tr.scale
            val rot = (tr.x * 13.1f + tr.z * 7.7f) % 6.28f
            val tint = 0.85f + Noise.value(tr.x * 0.3f, tr.z * 0.3f) * 0.3f
            if (tr.pine) {
                b.prism(tr.x, tr.y - 0.5f, tr.z, 0.32f * s, 2.6f * s, 8, TRUNK, topRadius = 0.18f * s)
                // Five drooping tiers, narrowing towards the top.
                val c = PINE.scale(tint)
                for (k in 0 until 5) {
                    val f = k / 4f
                    b.cone(tr.x, tr.y + (1.1f + k * 1.25f) * s, tr.z, (2.8f - f * 1.9f) * s, (2.9f - f * 0.6f) * s, 10, c.scale(1f + f * 0.14f), rot + k * 0.4f)
                }
            } else {
                b.prism(tr.x, tr.y - 0.5f, tr.z, 0.38f * s, 3.8f * s, 8, TRUNK, topRadius = 0.22f * s)
                val autumn = Noise.value(tr.x * 0.05f, tr.z * 0.05f)
                val leaf = LEAF.mix(LEAF_AUTUMN, clamp(autumn - 0.55f, 0f, 1f) * 2f).scale(tint)
                val cy = tr.y + 4.4f * s
                val seed = (tr.x * 3 + tr.z).toInt()
                // A crown of overlapping clumps.
                b.blob(V3(tr.x, cy, tr.z), 2.3f * s, 1.9f * s, 2.3f * s, 5, 10, leaf, seed)
                for (k in 0 until 3) {
                    val a = rot + k * 2.1f
                    b.blob(
                        V3(tr.x + cos(a) * 1.4f * s, cy + (0.5f + 0.4f * (k % 2)) * s, tr.z + sin(a) * 1.4f * s),
                        1.45f * s, 1.25f * s, 1.45f * s, 4, 8, leaf.scale(0.95f + 0.05f * k), seed + k * 7,
                    )
                }
                b.blob(V3(tr.x, cy + 1.6f * s, tr.z), 1.5f * s, 1.2f * s, 1.5f * s, 4, 8, leaf.scale(1.1f), seed + 3)
            }
        }
        for (bush in world.bushes) {
            val tint = 0.85f + Noise.value(bush.x * 0.4f, bush.z * 0.4f) * 0.3f
            b.blob(
                V3(bush.x, bush.size * 0.45f, bush.z), bush.size * 1.1f, bush.size * 0.8f, bush.size,
                4, 8, BUSH.scale(tint), (bush.x + bush.z).toInt(),
            )
        }
        for (r in world.rocks) {
            val s = r.size
            b.blob(V3(r.x, r.y + s * 0.2f, r.z), s * 1.2f, s * 0.75f, s, 4, 8, ROCK.scale(0.8f + Noise.value(r.x, r.z) * 0.4f), r.x.toInt())
        }
        return b.toArray()
    }
}
