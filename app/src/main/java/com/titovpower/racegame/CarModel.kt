package com.titovpower.racegame

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural sports car. Local +z is forward, +x is the car's left, y is up,
 * origin on the ground at the car's center.
 */
object CarModel {
    const val WHEEL_X = 0.83f
    const val FRONT_AXLE = 1.38f
    const val REAR_AXLE = -1.32f
    private const val HALF_WIDTH = 0.98f
    private const val ARCH_RADIUS = 0.40f

    private val PAINT = Rgb.hex(0xC8102E)
    private val TRIM = Rgb.hex(0x18191B)
    private val GLASS = Rgb.hex(0x0D1520)
    private val CHROME = Rgb.hex(0xC9CED4)

    private class P(val z: Float, val y: Float, val round: Float)

    /** Side silhouette (z, y), counter-clockwise from the rear bottom, with both wheel arches cut out. */
    private fun sideProfile(): List<P> {
        val pts = ArrayList<P>()
        fun arch(axle: Float) {
            val cy = CarPhysics.WHEEL_RADIUS
            // From the arch's rear foot over the top to its front foot.
            pts.add(P(axle - ARCH_RADIUS - 0.02f, 0.27f, 0f))
            val steps = 9
            for (k in 0..steps) {
                val a = (PI - k * PI / steps).toFloat()
                pts.add(P(axle + cos(a) * ARCH_RADIUS, cy + sin(a) * ARCH_RADIUS, 0f))
            }
            pts.add(P(axle + ARCH_RADIUS + 0.02f, 0.27f, 0f))
        }
        pts.add(P(-2.22f, 0.34f, 0.4f))
        arch(REAR_AXLE)
        pts.add(P(0f, 0.25f, 0f))
        arch(FRONT_AXLE)
        pts.add(P(2.12f, 0.29f, 0.3f))
        // Nose and hood.
        pts.add(P(2.27f, 0.38f, 0.5f))
        pts.add(P(2.28f, 0.48f, 0.7f))
        pts.add(P(2.18f, 0.57f, 0.9f))
        pts.add(P(1.9f, 0.68f, 1f))
        pts.add(P(1.5f, 0.77f, 1f))
        pts.add(P(1.05f, 0.82f, 1f))
        pts.add(P(0.6f, 0.84f, 1f))
        // Under the cabin.
        pts.add(P(0f, 0.86f, 1f))
        pts.add(P(-0.8f, 0.87f, 1f))
        pts.add(P(-1.5f, 0.89f, 1f))
        // Rear deck and tail.
        pts.add(P(-1.95f, 0.9f, 1f))
        pts.add(P(-2.17f, 0.86f, 0.8f))
        pts.add(P(-2.26f, 0.72f, 0.6f))
        pts.add(P(-2.27f, 0.5f, 0.5f))
        return pts
    }

    /** Minimum shoulder height around the wheels, fading out smoothly so the fenders bulge without a crease. */
    private fun archTop(z: Float): Float {
        var top = 0f
        for (axle in floatArrayOf(FRONT_AXLE, REAR_AXLE)) {
            val t = clamp((kotlin.math.abs(z - axle) - 0.3f) / 0.45f, 0f, 1f)
            val w = 0.5f + 0.5f * cos(t * PI.toFloat())
            top = maxOf(top, (CarPhysics.WHEEL_RADIUS + ARCH_RADIUS + 0.06f) * w)
        }
        return top
    }

    fun body(paint: Rgb = PAINT): FloatArray {
        val b = MeshBuilder(1 shl 15)
        val profile = sideProfile()

        // Lofted across the width; upper points are pulled in near the sides for round shoulders.
        val us = floatArrayOf(-1f, -0.97f, -0.9f, -0.75f, -0.45f, 0f, 0.45f, 0.75f, 0.9f, 0.97f, 1f)
        val rings = us.map { u ->
            val s = sqrt(maxOf(0f, 1f - u * u)).coerceAtLeast(0.3f)
            val shrink = 1f - s
            profile.map { p ->
                // Never pull the shoulders below the wheel arches: that leaves fender bulges.
                val y = if (p.round == 0f) p.y else maxOf(p.y - shrink * p.round * 0.22f, archTop(p.z))
                val z = p.z * (1f - shrink * p.round * 0.05f)
                // Slight tumblehome: the sides lean in towards the top.
                // Rounder nose and tail in plan view.
                val taper = 1f - 0.16f * clamp((z - 1.9f) / 0.38f, 0f, 1f) - 0.06f * clamp((-z - 2.0f) / 0.27f, 0f, 1f)
                val x = u * taper * (HALF_WIDTH - 0.06f * clamp((y - 0.55f) / 0.35f, 0f, 1f))
                V3(x, y, z)
            }
        }
        b.loft(
            rings,
            colorAt = { i, j -> if (rings[i][j].y < 0.36f) TRIM else paint },
            glossAt = { i, j -> if (rings[i][j].y < 0.36f) 0.2f else 0.75f },
            capStart = true, capEnd = true,
        )

        // Greenhouse: glass cabin with a painted roof.
        data class Sec(val z: Float, val baseW: Float, val topW: Float, val baseY: Float, val roofY: Float)
        val secs = listOf(
            Sec(-1.62f, 0.7f, 0.3f, 0.86f, 0.88f),
            Sec(-1.35f, 0.76f, 0.52f, 0.86f, 1.02f),
            Sec(-0.95f, 0.78f, 0.6f, 0.85f, 1.16f),
            Sec(-0.45f, 0.79f, 0.62f, 0.84f, 1.21f),
            Sec(0f, 0.79f, 0.61f, 0.83f, 1.2f),
            Sec(0.32f, 0.79f, 0.58f, 0.82f, 1.1f),
            Sec(0.62f, 0.78f, 0.5f, 0.8f, 0.95f),
            Sec(0.9f, 0.74f, 0.4f, 0.78f, 0.81f),
        )
        val segs = 20
        val cabin = secs.map { sc ->
            (0 until segs).map { k ->
                val a = (k * 2 * PI / segs).toFloat()
                val c = cos(a); val s = sin(a)
                // Superellipse cross-section: flat-ish sides, rounded corners.
                val sx = Math.copySign(Math.pow(kotlin.math.abs(c).toDouble(), 0.5).toFloat(), c)
                val sy = Math.copySign(Math.pow(kotlin.math.abs(s).toDouble(), 0.5).toFloat(), s)
                val t = (sy + 1f) * 0.5f
                V3(sx * (sc.baseW + (sc.topW - sc.baseW) * t), sc.baseY - 0.05f + t * (sc.roofY - sc.baseY + 0.05f), sc.z)
            }
        }
        b.loft(
            cabin,
            colorAt = { i, j ->
                val p = cabin[i][j]
                val roof = p.y > secs[i].roofY - 0.035f && i in 2..5
                if (roof) paint else GLASS
            },
            glossAt = { i, j -> if (cabin[i][j].y > secs[i].roofY - 0.035f && i in 2..5) 0.75f else 1f },
            capStart = true, capEnd = true,
        )

        // Floor pan closes the gap between the wheel arches.
        b.gloss = 0.1f
        b.box(0f, 0.3f, 0f, 0.66f, 0.06f, 2.0f, TRIM)
        // Rear diffuser and exhaust tips.
        b.box(0f, 0.31f, -2.2f, 0.7f, 0.07f, 0.08f, TRIM)
        b.gloss = 0.9f
        for (x in floatArrayOf(-0.35f, 0.35f)) {
            b.blob(V3(x, 0.37f, -2.25f), 0.06f, 0.045f, 0.06f, 3, 10, CHROME.scale(0.55f))
        }
        // Rear wing: two struts and a thin wedge-shaped blade.
        b.gloss = 0.3f
        for (x in floatArrayOf(-0.55f, 0.55f)) b.box(x, 0.98f, -1.98f, 0.03f, 0.1f, 0.06f, TRIM)
        b.gloss = 0.75f
        b.hexahedron(
            arrayOf(
                V3(-0.92f, 1.07f, -2.2f), V3(0.92f, 1.07f, -2.2f), V3(0.92f, 1.08f, -1.86f), V3(-0.92f, 1.08f, -1.86f),
                V3(-0.92f, 1.12f, -2.2f), V3(0.92f, 1.12f, -2.2f), V3(0.92f, 1.1f, -1.86f), V3(-0.92f, 1.1f, -1.86f),
            ),
            paint,
        )
        for (x in floatArrayOf(-0.93f, 0.93f)) b.box(x, 1.08f, -2.03f, 0.015f, 0.08f, 0.2f, TRIM)
        // Side mirrors.
        for (side in floatArrayOf(-1f, 1f)) {
            b.gloss = 0.3f
            b.box(side * 0.83f, 0.93f, 0.58f, 0.06f, 0.015f, 0.03f, TRIM)
            b.gloss = 0.75f
            b.blob(V3(side * 0.92f, 0.96f, 0.56f), 0.09f, 0.055f, 0.07f, 4, 8, paint)
        }
        // Side air intakes ahead of the rear wheels.
        b.gloss = 0.2f
        for (side in floatArrayOf(-1f, 1f)) {
            b.orientedBox(V3(side * (HALF_WIDTH - 0.01f), 0.55f, -0.62f), V3(0.02f, 0f, 0f), V3(0f, 0.08f, 0f), V3(0f, 0.02f, 0.22f), TRIM)
        }
        // Headlights: flattened glass lenses set into the nose.
        b.gloss = 1f
        for (side in floatArrayOf(-1f, 1f)) {
            b.blob(V3(side * 0.6f, 0.545f, 2.1f), 0.2f, 0.03f, 0.1f, 4, 10, Rgb(0.82f, 0.86f, 0.9f))
        }
        b.gloss = 0.2f
        b.orientedBox(V3(0f, 0.36f, 2.24f), V3(0.55f, 0f, 0f), V3(0f, 0.05f, 0f), V3(0f, 0f, 0.04f), TRIM)
        b.gloss = 0f
        return b.toArray()
    }

    /** Tail lights: drawn dim normally and bright (unlit) while braking. */
    fun tailLights(): FloatArray {
        val b = MeshBuilder()
        b.gloss = 0.8f
        val red = Rgb(0.85f, 0.04f, 0.04f)
        for (side in floatArrayOf(-1f, 1f)) {
            b.orientedBox(V3(side * 0.6f, 0.68f, -2.255f), V3(0.24f, 0f, 0f), V3(0f, 0.035f, 0f), V3(0f, 0f, 0.02f), red)
        }
        // Center light bar.
        b.orientedBox(V3(0f, 0.68f, -2.255f), V3(0.3f, 0f, 0f), V3(0f, 0.012f, 0f), V3(0f, 0f, 0.015f), red)
        return b.toArray()
    }

    /** One wheel, outer face towards +x, axle along x, centered at the origin. */
    fun wheel(): FloatArray {
        val b = MeshBuilder(1 shl 13)
        val r = CarPhysics.WHEEL_RADIUS
        // Tire: a rounded cross-section swept around the axle.
        val profile = listOf(
            0.15f to 0.235f, 0.158f to 0.28f, 0.145f to r - 0.012f, 0.115f to r,
            -0.115f to r, -0.145f to r - 0.012f, -0.158f to 0.28f, -0.15f to 0.235f,
        )
        val segs = 24
        val rings = (0..segs).map { k ->
            val a = (k * 2 * PI / segs).toFloat()
            profile.map { (x, rad) -> V3(x, cos(a) * rad, sin(a) * rad) }
        }
        val tire = Rgb.hex(0x1C1C1E)
        b.loft(rings, { _, _ -> tire }, { _, _ -> 0.12f }, capStart = false, capEnd = false)

        fun disc(x: Float, radius: Float, color: Rgb, gloss: Float, inner: Float = 0f) {
            b.gloss = gloss
            val n = V3(1f, 0f, 0f)
            for (k in 0 until segs) {
                val a0 = (k * 2 * PI / segs).toFloat()
                val a1 = ((k + 1) * 2 * PI / segs).toFloat()
                val o0 = V3(x, cos(a0) * radius, sin(a0) * radius)
                val o1 = V3(x, cos(a1) * radius, sin(a1) * radius)
                val i0 = V3(x, cos(a0) * inner, sin(a0) * inner)
                val i1 = V3(x, cos(a1) * inner, sin(a1) * inner)
                b.vertex(i0, n, color); b.vertex(o0, n, color); b.vertex(o1, n, color)
                b.vertex(i0, n, color); b.vertex(o1, n, color); b.vertex(i1, n, color)
            }
        }
        // Dark rim barrel, brake disc, polished rim lip.
        disc(0.06f, 0.235f, Rgb.hex(0x2A2C30), 0.3f)
        disc(0.09f, 0.18f, Rgb.hex(0x6D6F73), 0.5f)
        disc(0.14f, 0.24f, CHROME, 0.9f, inner = 0.215f)
        // Five spokes and a hub.
        b.gloss = 0.9f
        for (k in 0 until 5) {
            val a = (k * 2 * PI / 5).toFloat()
            val dir = V3(0f, cos(a), sin(a))
            val side = V3(0f, -sin(a), cos(a))
            b.orientedBox(V3(0.13f, 0f, 0f) + dir * 0.125f, V3(0.012f, 0f, 0f), dir * 0.095f, side * 0.026f, CHROME)
        }
        b.blob(V3(0.135f, 0f, 0f), 0.02f, 0.05f, 0.05f, 4, 8, CHROME)
        b.gloss = 0f
        return b.toArray()
    }

    /** Soft contact shadow under the car (unlit, alpha in the gloss channel). */
    fun shadow(): FloatArray {
        val b = MeshBuilder()
        b.softDisc(0f, 0.03f, 0f, 1.25f, 2.6f, 24, 0.62f)
        return b.toArray()
    }
}
