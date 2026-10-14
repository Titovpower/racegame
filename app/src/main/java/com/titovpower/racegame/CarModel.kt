package com.titovpower.racegame

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural sports cars styled after six real models (proportions from published dimensions;
 * no logos). Local +z is forward, +x is the car's left, y is up, origin on the ground at the
 * car's center.
 */
class CarStyle(
    val key: String,
    val length: Float,
    val halfWidth: Float,
    val frontAxle: Float,
    val rearAxle: Float,
    /** Height of the front and rear faces' lower edge. */
    val noseLowY: Float,
    val tailLowY: Float,
    /** Top silhouette from the nose (z = length/2) back to the tail (z = -length/2), z decreasing. */
    val top: List<Pair<Float, Float>>,
    /** Greenhouse: windshield base, roof front, roof rear, rear-glass base (z), and roof height. */
    val windshieldZ: Float,
    val roofFrontZ: Float,
    val roofRearZ: Float,
    val rearGlassZ: Float,
    val roofY: Float,
    val grille: Grille,
    val headlights: Headlights,
    val wing: Wing,
    val exhaust: Exhaust,
    val taillights: Taillights,
    val sideIntake: Boolean = false,
    val sideBlade: Boolean = false,
) {
    enum class Grille { LAMBO, PORSCHE, KIDNEY, PANAMERICANA, SINGLEFRAME, ASTON }
    enum class Headlights { Y_SHAPE, ROUND, ANGULAR, SLIM }
    enum class Wing { LIP, SWAN_NECK, FIXED, NONE }
    enum class Exhaust { CENTER_HIGH, CENTER_PAIR, QUAD, OVAL_PAIR }
    enum class Taillights { Y_SHAPE, FULL_BAR, L_SHAPE, SLIM_PAIR }

    val wheelX get() = halfWidth - 0.16f
    val front get() = length / 2f
    val rear get() = -length / 2f

    /** Height of the top silhouette at z (linear between control points). */
    fun topY(z: Float): Float {
        if (z >= top.first().first) return top.first().second
        for (k in 0 until top.size - 1) {
            val (z0, y0) = top[k]
            val (z1, y1) = top[k + 1]
            if (z <= z0 && z >= z1) return y0 + (y1 - y0) * (z0 - z) / (z0 - z1)
        }
        return top.last().second
    }
}

object CarModel {
    private const val ARCH_RADIUS = 0.41f
    private const val WHEEL_RADIUS = 0.345f

    private val TRIM = Rgb.hex(0x16171A)
    private val CARBON = Rgb.hex(0x25272B)
    private val GLASS = Rgb.hex(0x0C131C)
    private val CHROME = Rgb.hex(0xC9CED4)
    private val LENS = Rgb(0.84f, 0.88f, 0.93f)
    private val DRL = Rgb(1f, 1f, 1f)

    val LAMBORGHINI = CarStyle(
        "lambo", 4.52f, 0.965f, 1.36f, -1.26f, 0.32f, 0.42f,
        listOf(2.26f to 0.40f, 2.1f to 0.55f, 1.6f to 0.66f, 0.9f to 0.79f, 0.2f to 0.82f, -0.7f to 0.86f, -1.0f to 0.98f, -1.8f to 1.0f, -2.15f to 0.98f, -2.26f to 0.9f),
        windshieldZ = 0.9f, roofFrontZ = -0.05f, roofRearZ = -0.7f, rearGlassZ = -1.05f, roofY = 1.16f,
        grille = CarStyle.Grille.LAMBO, headlights = CarStyle.Headlights.Y_SHAPE, wing = CarStyle.Wing.LIP,
        exhaust = CarStyle.Exhaust.CENTER_HIGH, taillights = CarStyle.Taillights.Y_SHAPE, sideIntake = true,
    )
    val PORSCHE = CarStyle(
        "911", 4.57f, 0.925f, 1.30f, -1.16f, 0.32f, 0.40f,
        listOf(2.28f to 0.52f, 2.1f to 0.62f, 1.5f to 0.72f, 0.75f to 0.85f, 0f to 0.87f, -0.9f to 0.9f, -1.8f to 0.93f, -2.2f to 0.92f, -2.28f to 0.8f),
        windshieldZ = 0.75f, roofFrontZ = 0.15f, roofRearZ = -0.55f, rearGlassZ = -1.85f, roofY = 1.28f,
        grille = CarStyle.Grille.PORSCHE, headlights = CarStyle.Headlights.ROUND, wing = CarStyle.Wing.SWAN_NECK,
        exhaust = CarStyle.Exhaust.CENTER_PAIR, taillights = CarStyle.Taillights.FULL_BAR,
    )
    val BMW = CarStyle(
        "m4", 4.79f, 0.945f, 1.50f, -1.36f, 0.30f, 0.45f,
        listOf(2.39f to 0.66f, 2.3f to 0.76f, 1.8f to 0.86f, 0.6f to 0.93f, 0f to 0.94f, -1.0f to 0.96f, -1.6f to 1.0f, -2.25f to 1.0f, -2.39f to 0.88f),
        windshieldZ = 0.6f, roofFrontZ = -0.15f, roofRearZ = -0.95f, rearGlassZ = -1.6f, roofY = 1.39f,
        grille = CarStyle.Grille.KIDNEY, headlights = CarStyle.Headlights.ANGULAR, wing = CarStyle.Wing.LIP,
        exhaust = CarStyle.Exhaust.QUAD, taillights = CarStyle.Taillights.L_SHAPE,
    )
    val MERCEDES = CarStyle(
        "amggt", 4.55f, 1.0f, 1.25f, -1.38f, 0.30f, 0.42f,
        listOf(2.27f to 0.55f, 2.12f to 0.66f, 1.4f to 0.82f, 0.3f to 0.94f, -0.4f to 0.92f, -1.2f to 0.92f, -1.9f to 0.95f, -2.2f to 0.94f, -2.27f to 0.82f),
        windshieldZ = 0.3f, roofFrontZ = -0.35f, roofRearZ = -0.95f, rearGlassZ = -1.9f, roofY = 1.28f,
        grille = CarStyle.Grille.PANAMERICANA, headlights = CarStyle.Headlights.SLIM, wing = CarStyle.Wing.FIXED,
        exhaust = CarStyle.Exhaust.CENTER_PAIR, taillights = CarStyle.Taillights.SLIM_PAIR,
    )
    val AUDI = CarStyle(
        "r8", 4.43f, 0.97f, 1.34f, -1.31f, 0.32f, 0.42f,
        listOf(2.21f to 0.45f, 2.05f to 0.58f, 1.5f to 0.7f, 0.85f to 0.82f, 0.2f to 0.84f, -0.65f to 0.88f, -0.95f to 1.0f, -1.85f to 1.02f, -2.15f to 1.0f, -2.21f to 0.88f),
        windshieldZ = 0.85f, roofFrontZ = 0f, roofRearZ = -0.65f, rearGlassZ = -1.0f, roofY = 1.24f,
        grille = CarStyle.Grille.SINGLEFRAME, headlights = CarStyle.Headlights.SLIM, wing = CarStyle.Wing.LIP,
        exhaust = CarStyle.Exhaust.OVAL_PAIR, taillights = CarStyle.Taillights.SLIM_PAIR, sideBlade = true,
    )
    val ASTON = CarStyle(
        "vantage", 4.50f, 0.99f, 1.38f, -1.32f, 0.30f, 0.44f,
        listOf(2.25f to 0.52f, 2.1f to 0.64f, 1.4f to 0.8f, 0.4f to 0.92f, -0.25f to 0.92f, -1.0f to 0.93f, -1.8f to 0.95f, -2.15f to 1.0f, -2.25f to 0.9f),
        windshieldZ = 0.4f, roofFrontZ = -0.25f, roofRearZ = -0.9f, rearGlassZ = -1.8f, roofY = 1.27f,
        grille = CarStyle.Grille.ASTON, headlights = CarStyle.Headlights.SLIM, wing = CarStyle.Wing.NONE,
        exhaust = CarStyle.Exhaust.QUAD, taillights = CarStyle.Taillights.FULL_BAR,
    )

    fun styleFor(spec: CarSpec): CarStyle = when (spec) {
        CarSpec.LAMBORGHINI -> LAMBORGHINI
        CarSpec.PORSCHE -> PORSCHE
        CarSpec.BMW -> BMW
        CarSpec.MERCEDES -> MERCEDES
        CarSpec.AUDI -> AUDI
        else -> ASTON
    }

    private class P(val z: Float, val y: Float, val round: Float)

    /** Side silhouette (z, y), counter-clockwise from the rear bottom, with both wheel arches cut out. */
    private fun sideProfile(st: CarStyle): List<P> {
        val pts = ArrayList<P>()
        fun arch(axle: Float) {
            pts.add(P(axle - ARCH_RADIUS - 0.02f, 0.27f, 0f))
            val steps = 9
            for (k in 0..steps) {
                val a = (PI - k * PI / steps).toFloat()
                pts.add(P(axle + cos(a) * ARCH_RADIUS, WHEEL_RADIUS + sin(a) * ARCH_RADIUS, 0f))
            }
            pts.add(P(axle + ARCH_RADIUS + 0.02f, 0.27f, 0f))
        }
        pts.add(P(st.rear + 0.06f, st.tailLowY - 0.08f, 0.4f))
        arch(st.rearAxle)
        pts.add(P((st.rearAxle + st.frontAxle) / 2f, 0.24f, 0f))
        arch(st.frontAxle)
        pts.add(P(st.front - 0.1f, st.noseLowY - 0.04f, 0.3f))
        pts.add(P(st.front - 0.01f, st.noseLowY + 0.05f, 0.5f))
        for ((k, p) in st.top.withIndex()) {
            val end = k == 0 || k == st.top.size - 1
            pts.add(P(p.first, p.second, if (end) 0.7f else 1f))
        }
        pts.add(P(st.rear, st.tailLowY, 0.5f))
        return pts
    }

    /** Minimum shoulder height around the wheels, fading out smoothly so the fenders bulge without a crease. */
    private fun archTop(st: CarStyle, z: Float): Float {
        var top = 0f
        for (axle in floatArrayOf(st.frontAxle, st.rearAxle)) {
            val t = clamp((abs(z - axle) - 0.3f) / 0.45f, 0f, 1f)
            val w = 0.5f + 0.5f * cos(t * PI.toFloat())
            top = maxOf(top, (WHEEL_RADIUS + ARCH_RADIUS + 0.06f) * w)
        }
        return top
    }

    fun body(st: CarStyle, paint: Rgb): FloatArray {
        val b = MeshBuilder(1 shl 15)
        val profile = sideProfile(st)
        val hw = st.halfWidth

        // Lofted across the width; upper points are pulled in near the sides for round shoulders.
        val us = floatArrayOf(-1f, -0.97f, -0.9f, -0.75f, -0.45f, 0f, 0.45f, 0.75f, 0.9f, 0.97f, 1f)
        val rings = us.map { u ->
            val s = sqrt(maxOf(0f, 1f - u * u)).coerceAtLeast(0.3f)
            val shrink = 1f - s
            profile.map { p ->
                // Never pull the shoulders below the wheel arches: that leaves fender bulges.
                val y = if (p.round == 0f) p.y else maxOf(p.y - shrink * p.round * 0.22f, archTop(st, p.z))
                val z = p.z * (1f - shrink * p.round * 0.04f)
                // Rounder nose and tail in plan view, and a slight tumblehome towards the top.
                val taper = 1f - 0.16f * clamp((z - (st.front - 0.38f)) / 0.38f, 0f, 1f) -
                    0.08f * clamp((st.rear + 0.3f - z) / 0.3f, 0f, 1f)
                V3(u * taper * (hw - 0.06f * clamp((y - 0.55f) / 0.35f, 0f, 1f)), y, z)
            }
        }
        val sill = 0.36f
        b.loft(
            rings,
            colorAt = { i, j -> if (rings[i][j].y < sill) TRIM else paint },
            glossAt = { i, j -> if (rings[i][j].y < sill) 0.2f else 0.8f },
            capStart = true, capEnd = true,
        )
        cabin(b, st, paint)

        // Underbody, splitter and diffuser.
        b.gloss = 0.1f
        b.box(0f, 0.3f, (st.front + st.rear) / 2f, hw - 0.3f, 0.06f, st.length / 2f - 0.3f, TRIM)
        b.box(0f, 0.27f, st.front - 0.2f, hw - 0.12f, 0.025f, 0.14f, CARBON)
        b.box(0f, 0.3f, st.rear + 0.12f, hw - 0.2f, 0.07f, 0.1f, CARBON)
        grille(b, st)
        headlights(b, st)
        wing(b, st, paint)
        exhaust(b, st)
        // Side mirrors.
        for (side in floatArrayOf(-1f, 1f)) {
            val z = st.windshieldZ - 0.15f
            val y = st.topY(z) + 0.05f
            b.gloss = 0.3f
            b.box(side * (hw - 0.14f), y - 0.02f, z, 0.07f, 0.015f, 0.03f, TRIM)
            b.gloss = 0.8f
            b.blob(V3(side * (hw - 0.04f), y, z - 0.02f), 0.09f, 0.055f, 0.07f, 4, 8, paint)
        }
        // Side details.
        for (side in floatArrayOf(-1f, 1f)) {
            val x = side * (hw - 0.02f)
            if (st.sideIntake) {
                // Large triangular intake ahead of the rear wheel.
                b.gloss = 0.2f
                val z0 = st.rearAxle + ARCH_RADIUS + 0.12f
                b.hexahedron(
                    arrayOf(
                        V3(x - side * 0.05f, 0.45f, z0), V3(x + side * 0.01f, 0.45f, z0), V3(x + side * 0.01f, 0.45f, z0 + 0.7f), V3(x - side * 0.05f, 0.45f, z0 + 0.7f),
                        V3(x - side * 0.05f, 0.8f, z0), V3(x + side * 0.01f, 0.8f, z0), V3(x + side * 0.01f, 0.5f, z0 + 0.7f), V3(x - side * 0.05f, 0.5f, z0 + 0.7f),
                    ),
                    TRIM,
                )
            }
            if (st.sideBlade) {
                // Contrasting vertical blade behind the doors.
                b.gloss = 0.5f
                b.orientedBox(V3(x, 0.6f, st.rearAxle + 0.62f), V3(0.02f, 0f, 0f), V3(0f, 0.26f, 0f), V3(0f, 0.02f, 0.14f), CARBON)
            }
        }
        b.gloss = 0f
        return b.toArray()
    }

    /** Glass greenhouse with painted roof and pillars, lofted along z. */
    private fun cabin(b: MeshBuilder, st: CarStyle, paint: Rgb) {
        val hw = st.halfWidth
        val zs = (0..9).map { k -> st.rearGlassZ + (st.windshieldZ - st.rearGlassZ) * k / 9f }
        val segs = 20
        fun roofAt(z: Float): Float {
            val base = st.topY(z)
            return when {
                z >= st.roofFrontZ -> base + (st.roofY - base) * smooth((st.windshieldZ - z) / (st.windshieldZ - st.roofFrontZ))
                z <= st.roofRearZ -> base + (st.roofY - base) * smooth((z - st.rearGlassZ) / (st.roofRearZ - st.rearGlassZ))
                else -> st.roofY
            }
        }
        val secs = zs.map { z ->
            val base = st.topY(z) - 0.04f
            val roof = maxOf(roofAt(z), base + 0.04f)
            Triple(z, base, roof)
        }
        val rings = secs.map { (z, base, roof) ->
            (0 until segs).map { k ->
                val a = (k * 2 * PI / segs).toFloat()
                val c = cos(a)
                val s = sin(a)
                val sx = Math.copySign(Math.pow(abs(c).toDouble(), 0.5).toFloat(), c)
                val sy = Math.copySign(Math.pow(abs(s).toDouble(), 0.5).toFloat(), s)
                val t = (sy + 1f) * 0.5f
                val w = hw * (0.84f + (0.6f - 0.84f) * t)
                V3(sx * w, base + t * (roof - base), z)
            }
        }
        fun isRoof(i: Int, j: Int): Boolean {
            val (z, _, roof) = secs[i]
            val p = rings[i][j]
            return p.y > roof - 0.03f && z < st.roofFrontZ + 0.05f && z > st.roofRearZ - 0.05f
        }
        b.loft(
            rings,
            colorAt = { i, j -> if (isRoof(i, j)) paint else GLASS },
            glossAt = { i, j -> if (isRoof(i, j)) 0.8f else 1f },
            capStart = true, capEnd = true,
        )
    }

    private fun smooth(t: Float): Float {
        val c = clamp(t, 0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Extruded convex polygon (x, y pairs) facing forward, between z0 and z1. */
    private fun plate(b: MeshBuilder, poly: List<Pair<Float, Float>>, z0: Float, z1: Float, color: Rgb) {
        val c = V3(poly.map { it.first }.average().toFloat(), poly.map { it.second }.average().toFloat(), (z0 + z1) / 2f)
        for (k in poly.indices) {
            val (x0, y0) = poly[k]
            val (x1, y1) = poly[(k + 1) % poly.size]
            b.quad(V3(x0, y0, z0), V3(x1, y1, z0), V3(x1, y1, z1), V3(x0, y0, z1), color, c)
            b.tri(V3(c.x, c.y, z1), V3(x0, y0, z1), V3(x1, y1, z1), color, c)
            b.tri(V3(c.x, c.y, z0), V3(x0, y0, z0), V3(x1, y1, z0), color, c)
        }
    }

    private fun grille(b: MeshBuilder, st: CarStyle) {
        val zf = st.front
        b.gloss = 0.3f
        when (st.grille) {
            CarStyle.Grille.LAMBO -> {
                // Big angular corner intakes and a dark center lip.
                for (side in floatArrayOf(-1f, 1f)) {
                    plate(b, listOf(side * 0.35f to 0.3f, side * 0.8f to 0.3f, side * 0.84f to 0.42f, side * 0.45f to 0.44f), zf - 0.2f, zf - 0.04f, TRIM)
                }
                plate(b, listOf(-0.3f to 0.3f, 0.3f to 0.3f, 0.25f to 0.36f, -0.25f to 0.36f), zf - 0.15f, zf - 0.02f, TRIM)
            }
            CarStyle.Grille.PORSCHE -> {
                plate(b, listOf(-0.4f to 0.3f, 0.4f to 0.3f, 0.36f to 0.42f, -0.36f to 0.42f), zf - 0.2f, zf - 0.03f, TRIM)
                for (side in floatArrayOf(-1f, 1f)) {
                    plate(b, listOf(side * 0.5f to 0.3f, side * 0.78f to 0.3f, side * 0.74f to 0.42f, side * 0.52f to 0.42f), zf - 0.25f, zf - 0.07f, TRIM)
                }
            }
            CarStyle.Grille.KIDNEY -> {
                // Two tall kidneys with chrome surrounds, and a wide lower intake.
                for (side in floatArrayOf(-1f, 1f)) {
                    val x = side * 0.17f
                    b.gloss = 0.9f
                    plate(b, listOf(x - 0.14f to 0.4f, x + 0.14f to 0.4f, x + 0.15f to 0.74f, x - 0.13f to 0.74f), zf - 0.12f, zf - 0.005f, CHROME)
                    b.gloss = 0.3f
                    plate(b, listOf(x - 0.12f to 0.42f, x + 0.12f to 0.42f, x + 0.13f to 0.72f, x - 0.11f to 0.72f), zf - 0.1f, zf + 0.005f, TRIM)
                }
                plate(b, listOf(-0.6f to 0.29f, 0.6f to 0.29f, 0.55f to 0.37f, -0.55f to 0.37f), zf - 0.2f, zf - 0.03f, TRIM)
            }
            CarStyle.Grille.PANAMERICANA -> {
                plate(b, listOf(-0.38f to 0.3f, 0.38f to 0.3f, 0.32f to 0.53f, -0.32f to 0.53f), zf - 0.14f, zf - 0.01f, TRIM)
                b.gloss = 0.9f
                for (k in -6..6) {
                    val x = k * 0.05f
                    b.orientedBox(V3(x, 0.415f, zf - 0.01f), V3(0.008f, 0f, 0f), V3(0f, 0.105f, 0f), V3(0f, 0f, 0.012f), CHROME)
                }
            }
            CarStyle.Grille.SINGLEFRAME -> {
                plate(b, listOf(-0.28f to 0.29f, 0.28f to 0.29f, 0.36f to 0.37f, 0.3f to 0.46f, -0.3f to 0.46f, -0.36f to 0.37f), zf - 0.2f, zf - 0.02f, TRIM)
                for (side in floatArrayOf(-1f, 1f)) {
                    plate(b, listOf(side * 0.5f to 0.29f, side * 0.78f to 0.29f, side * 0.74f to 0.38f, side * 0.52f to 0.4f), zf - 0.25f, zf - 0.07f, TRIM)
                }
            }
            CarStyle.Grille.ASTON -> {
                plate(b, listOf(-0.45f to 0.29f, 0.45f to 0.29f, 0.38f to 0.5f, -0.38f to 0.5f), zf - 0.14f, zf - 0.01f, TRIM)
                b.gloss = 0.9f
                for (k in 0..2) {
                    val y = 0.34f + k * 0.055f
                    b.orientedBox(V3(0f, y, zf - 0.005f), V3(0.42f - k * 0.02f, 0f, 0f), V3(0f, 0.006f, 0f), V3(0f, 0f, 0.01f), CHROME)
                }
            }
        }
    }

    private fun headlights(b: MeshBuilder, st: CarStyle) {
        val hw = st.halfWidth
        for (side in floatArrayOf(-1f, 1f)) {
            when (st.headlights) {
                CarStyle.Headlights.ROUND -> {
                    // Upright round lamps at the front of the fenders.
                    val z = st.front - 0.3f
                    val y = archTop(st, z).coerceAtLeast(st.topY(z)) - 0.04f
                    b.gloss = 1f
                    b.blob(V3(side * (hw - 0.33f), y, z), 0.12f, 0.1f, 0.12f, 5, 12, LENS)
                    b.blob(V3(side * (hw - 0.33f), y, z + 0.07f), 0.07f, 0.06f, 0.05f, 4, 10, DRL)
                }
                CarStyle.Headlights.Y_SHAPE -> {
                    val z = st.front - 0.24f
                    val y = st.topY(z) - 0.065f
                    b.gloss = 1f
                    b.blob(V3(side * (hw - 0.3f), y, z), 0.22f, 0.025f, 0.1f, 4, 10, LENS)
                    // Y-shaped daytime running light: two angled strokes.
                    b.orientedBox(V3(side * (hw - 0.36f), y + 0.012f, z + 0.03f), V3(0.11f, 0f, side * -0.03f), V3(0f, 0.008f, 0f), V3(0f, 0f, 0.012f), DRL)
                    b.orientedBox(V3(side * (hw - 0.22f), y + 0.012f, z - 0.02f), V3(0.07f, 0f, side * 0.04f), V3(0f, 0.008f, 0f), V3(0f, 0f, 0.012f), DRL)
                }
                CarStyle.Headlights.ANGULAR -> {
                    val z = st.front - 0.12f
                    val y = st.topY(z) - 0.08f
                    b.gloss = 1f
                    b.orientedBox(V3(side * (hw - 0.3f), y, z), V3(0.2f, 0f, -side * 0.03f), V3(0f, 0.05f, -0.01f), V3(0f, 0f, 0.06f), LENS)
                    b.orientedBox(V3(side * (hw - 0.3f), y - 0.035f, z + 0.05f), V3(0.18f, 0f, 0f), V3(0f, 0.008f, 0f), V3(0f, 0f, 0.01f), DRL)
                }
                CarStyle.Headlights.SLIM -> {
                    val z = st.front - 0.24f
                    val y = st.topY(z) - 0.065f
                    b.gloss = 1f
                    b.blob(V3(side * (hw - 0.3f), y, z), 0.22f, 0.03f, 0.1f, 4, 10, LENS)
                    b.orientedBox(V3(side * (hw - 0.3f), y - 0.005f, z + 0.02f), V3(0.18f, 0f, -side * 0.02f), V3(0f, 0.006f, 0f), V3(0f, 0f, 0.012f), DRL)
                }
            }
        }
    }

    private fun wing(b: MeshBuilder, st: CarStyle, paint: Rgb) {
        val hw = st.halfWidth
        val zr = st.rear
        when (st.wing) {
            CarStyle.Wing.LIP -> {
                val y = st.topY(zr + 0.12f)
                b.gloss = 0.5f
                b.hexahedron(
                    arrayOf(
                        V3(-hw + 0.15f, y - 0.01f, zr + 0.04f), V3(hw - 0.15f, y - 0.01f, zr + 0.04f), V3(hw - 0.15f, y - 0.01f, zr + 0.25f), V3(-hw + 0.15f, y - 0.01f, zr + 0.25f),
                        V3(-hw + 0.15f, y + 0.06f, zr + 0.02f), V3(hw - 0.15f, y + 0.06f, zr + 0.02f), V3(hw - 0.15f, y + 0.01f, zr + 0.25f), V3(-hw + 0.15f, y + 0.01f, zr + 0.25f),
                    ),
                    CARBON,
                )
            }
            CarStyle.Wing.SWAN_NECK, CarStyle.Wing.FIXED -> {
                val z = zr + 0.32f
                val deck = st.topY(z + 0.15f)
                val wingY = deck + if (st.wing == CarStyle.Wing.SWAN_NECK) 0.36f else 0.28f
                b.gloss = 0.4f
                for (x in floatArrayOf(-0.45f, 0.45f)) {
                    if (st.wing == CarStyle.Wing.SWAN_NECK) {
                        // Swan-neck struts hang the wing from above.
                        b.orientedBox(V3(x, (deck + wingY) / 2f + 0.03f, z + 0.12f), V3(0.012f, 0f, 0f), V3(0f, (wingY - deck) / 2f + 0.03f, -0.06f), V3(0f, 0.02f, 0.06f), TRIM)
                    } else {
                        b.box(x, (deck + wingY) / 2f, z, 0.015f, (wingY - deck) / 2f, 0.07f, TRIM)
                    }
                }
                b.gloss = 0.6f
                b.hexahedron(
                    arrayOf(
                        V3(-hw + 0.05f, wingY - 0.03f, z - 0.2f), V3(hw - 0.05f, wingY - 0.03f, z - 0.2f), V3(hw - 0.05f, wingY - 0.01f, z + 0.15f), V3(-hw + 0.05f, wingY - 0.01f, z + 0.15f),
                        V3(-hw + 0.05f, wingY + 0.03f, z - 0.2f), V3(hw - 0.05f, wingY + 0.03f, z - 0.2f), V3(hw - 0.05f, wingY + 0.01f, z + 0.15f), V3(-hw + 0.05f, wingY + 0.01f, z + 0.15f),
                    ),
                    if (st.wing == CarStyle.Wing.SWAN_NECK) CARBON else paint,
                )
                for (side in floatArrayOf(-1f, 1f)) b.box(side * (hw - 0.04f), wingY, z - 0.03f, 0.012f, 0.08f, 0.2f, CARBON)
            }
            CarStyle.Wing.NONE -> Unit
        }
    }

    private fun exhaust(b: MeshBuilder, st: CarStyle) {
        val z = st.rear + 0.03f
        val tip = CHROME.scale(0.6f)
        b.gloss = 0.9f
        fun pipe(x: Float, y: Float, r: Float, w: Float = r) = b.blob(V3(x, y, z), w, r, 0.06f, 3, 12, tip)
        when (st.exhaust) {
            CarStyle.Exhaust.CENTER_HIGH -> { pipe(-0.09f, 0.62f, 0.05f); pipe(0.09f, 0.62f, 0.05f) }
            CarStyle.Exhaust.CENTER_PAIR -> { pipe(-0.1f, 0.36f, 0.055f); pipe(0.1f, 0.36f, 0.055f) }
            CarStyle.Exhaust.QUAD -> for (x in floatArrayOf(-0.62f, -0.48f, 0.48f, 0.62f)) pipe(x, 0.35f, 0.045f)
            CarStyle.Exhaust.OVAL_PAIR -> { pipe(-0.55f, 0.36f, 0.04f, 0.09f); pipe(0.55f, 0.36f, 0.04f, 0.09f) }
        }
    }

    /** Tail lights for [st]: drawn dim normally and bright (unlit) while braking. */
    fun tailLights(st: CarStyle): FloatArray {
        val b = MeshBuilder()
        b.gloss = 0.8f
        val red = Rgb(0.85f, 0.04f, 0.04f)
        val z = st.rear + 0.035f
        val y = st.topY(st.rear + 0.05f) - 0.12f
        val hw = st.halfWidth
        when (st.taillights) {
            CarStyle.Taillights.FULL_BAR -> {
                b.orientedBox(V3(0f, y, z), V3(hw - 0.2f, 0f, 0f), V3(0f, 0.022f, 0f), V3(0f, 0f, 0.02f), red)
                for (side in floatArrayOf(-1f, 1f)) b.orientedBox(V3(side * (hw - 0.3f), y, z), V3(0.12f, 0f, 0f), V3(0f, 0.04f, 0f), V3(0f, 0f, 0.022f), red)
            }
            CarStyle.Taillights.Y_SHAPE -> for (side in floatArrayOf(-1f, 1f)) {
                val cx = side * (hw - 0.32f)
                b.orientedBox(V3(cx, y + 0.03f, z), V3(0.16f, -side * 0.03f, 0f), V3(0f, 0.015f, 0f), V3(0f, 0f, 0.02f), red)
                b.orientedBox(V3(cx, y - 0.03f, z), V3(0.16f, side * 0.03f, 0f), V3(0f, 0.015f, 0f), V3(0f, 0f, 0.02f), red)
            }
            CarStyle.Taillights.L_SHAPE -> for (side in floatArrayOf(-1f, 1f)) {
                val cx = side * (hw - 0.3f)
                b.orientedBox(V3(cx, y, z), V3(0.2f, 0f, 0f), V3(0f, 0.035f, 0f), V3(0f, 0f, 0.02f), red)
                b.orientedBox(V3(cx + side * 0.17f, y - 0.06f, z), V3(0.03f, 0f, 0f), V3(0f, 0.05f, 0f), V3(0f, 0f, 0.02f), red)
            }
            CarStyle.Taillights.SLIM_PAIR -> for (side in floatArrayOf(-1f, 1f)) {
                b.orientedBox(V3(side * (hw - 0.3f), y, z), V3(0.22f, 0f, 0f), V3(0f, 0.025f, 0f), V3(0f, 0f, 0.02f), red)
            }
        }
        return b.toArray()
    }

    /** One wheel, outer face towards +x, axle along x, centered at the origin. */
    fun wheel(): FloatArray {
        val b = MeshBuilder(1 shl 13)
        val r = WHEEL_RADIUS
        // Tire: a rounded cross-section swept around the axle.
        val profile = listOf(
            0.15f to 0.24f, 0.158f to 0.285f, 0.145f to r - 0.012f, 0.115f to r,
            -0.115f to r, -0.145f to r - 0.012f, -0.158f to 0.285f, -0.15f to 0.24f,
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
        disc(0.06f, 0.24f, Rgb.hex(0x2A2C30), 0.3f)
        disc(0.09f, 0.19f, Rgb.hex(0x6D6F73), 0.5f)
        disc(0.14f, 0.245f, CHROME, 0.9f, inner = 0.22f)
        // Ten thin spokes in five pairs, and a center lock.
        b.gloss = 0.9f
        for (k in 0 until 10) {
            val a = (k * 2 * PI / 10).toFloat() + if (k % 2 == 0) 0.08f else -0.08f
            val dir = V3(0f, cos(a), sin(a))
            val side = V3(0f, -sin(a), cos(a))
            b.orientedBox(V3(0.13f, 0f, 0f) + dir * 0.13f, V3(0.012f, 0f, 0f), dir * 0.1f, side * 0.014f, CHROME)
        }
        b.blob(V3(0.135f, 0f, 0f), 0.02f, 0.05f, 0.05f, 4, 8, CHROME)
        b.gloss = 0f
        return b.toArray()
    }

    /** Soft contact shadow under the car (unlit, alpha in the gloss channel). */
    fun shadow(): FloatArray {
        val b = MeshBuilder()
        b.softDisc(0f, 0.03f, 0f, 1.1f, 2.4f, 24, 0.38f)
        return b.toArray()
    }

    const val WHEEL_RADIUS_RENDER = WHEEL_RADIUS
}
