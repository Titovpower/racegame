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
    /** How far the front fender crowns stand above the hood's center line (911: tall fenders, low hood). */
    val hoodDip: Float = 0.03f,
    /** Rear fender haunches above the deck. */
    val hipRise: Float = 0.03f,
    /** Plan-view width at the front axle relative to the rear (wide rear hips < 1). */
    val frontWidth: Float = 0.97f,
    /** Greenhouse half-width at its base and at the roof, relative to the body. */
    val cabinBase: Float = 0.84f,
    val cabinTop: Float = 0.6f,
    /** Wing height above the deck (0 = the wing type's default). */
    val wingRise: Float = 0f,
    /** Wing position: distance of its center from the tail. */
    val wingZ: Float = 0.32f,
) {
    enum class Grille { LAMBO, PORSCHE, KIDNEY, PANAMERICANA, SINGLEFRAME, ASTON, ONE }
    enum class Headlights { Y_SHAPE, ROUND, ANGULAR, SLIM, SWEPT }
    enum class Wing { LIP, SWAN_NECK, FIXED, NONE }
    enum class Exhaust { CENTER_HIGH, CENTER_PAIR, QUAD, OVAL_PAIR }
    enum class Taillights { Y_SHAPE, FULL_BAR, L_SHAPE, SLIM_PAIR, BLADE }

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
    private val WELL = Rgb.hex(0x0E0E10)
    private val BRONZE = Rgb.hex(0xC08A4E)
    private val GLASS = Rgb.hex(0x0C131C)
    private val CHROME = Rgb.hex(0xC9CED4)
    /** Clear headlight glass over a dark reflector: reads as glass, with the light signature in white. */
    private val LENS = Rgb(0.3f, 0.32f, 0.36f)
    private val DRL = Rgb(1f, 1f, 1f)

    val LAMBORGHINI = CarStyle(
        // From Andy's reference photo (Huracán Super Trofeo EVO2 race car): wedge nose with huge intakes and
        // a wide splitter, hood vents, a roof scoop and a big swan-neck wing.
        "lambo", 4.52f, 0.965f, 1.36f, -1.26f, 0.32f, 0.42f,
        listOf(2.26f to 0.40f, 2.1f to 0.55f, 1.6f to 0.66f, 0.9f to 0.79f, 0.2f to 0.82f, -0.7f to 0.86f, -1.0f to 0.98f, -1.8f to 1.0f, -2.15f to 0.98f, -2.26f to 0.9f),
        windshieldZ = 0.9f, roofFrontZ = -0.05f, roofRearZ = -0.7f, rearGlassZ = -1.05f, roofY = 1.16f,
        grille = CarStyle.Grille.LAMBO, headlights = CarStyle.Headlights.Y_SHAPE, wing = CarStyle.Wing.SWAN_NECK,
        exhaust = CarStyle.Exhaust.CENTER_HIGH, taillights = CarStyle.Taillights.Y_SHAPE, sideIntake = true,
        hoodDip = 0.02f, hipRise = 0.04f, frontWidth = 0.98f, cabinBase = 0.78f, cabinTop = 0.52f,
        // Super Trofeo EVO2 (Andy's reference photo): a big wing on swan necks, overhanging the tail.
        wingRise = 0.44f, wingZ = 0.22f,
    )
    val PORSCHE = CarStyle(
        // From Andy's reference photos (911 GT3 RS): tall fenders over a low hood with two big vents,
        // a wide black lower intake, a huge swan-neck wing and intakes behind the doors.
        "911", 4.57f, 0.925f, 1.30f, -1.16f, 0.32f, 0.40f,
        listOf(2.28f to 0.52f, 2.1f to 0.62f, 1.5f to 0.72f, 0.75f to 0.85f, 0f to 0.87f, -0.9f to 0.9f, -1.8f to 0.93f, -2.2f to 0.92f, -2.28f to 0.8f),
        windshieldZ = 0.75f, roofFrontZ = 0.15f, roofRearZ = -0.55f, rearGlassZ = -1.85f, roofY = 1.28f,
        grille = CarStyle.Grille.PORSCHE, headlights = CarStyle.Headlights.ROUND, wing = CarStyle.Wing.SWAN_NECK,
        exhaust = CarStyle.Exhaust.CENTER_PAIR, taillights = CarStyle.Taillights.FULL_BAR,
        hoodDip = 0.1f, hipRise = 0.07f, frontWidth = 0.93f, cabinBase = 0.8f, cabinTop = 0.6f,
        wingRise = 0.56f, wingZ = 0.42f,
    )
    val BMW = CarStyle(
        "m4", 4.79f, 0.945f, 1.50f, -1.36f, 0.30f, 0.45f,
        // From Andy's reference photo (a BMW coupe concept): tall nose carrying huge kidneys, sculpted
        // hood, slit headlights, deep angular intakes and a long fastback roof.
        listOf(2.39f to 0.76f, 2.3f to 0.83f, 1.8f to 0.88f, 0.6f to 0.93f, 0f to 0.94f, -1.0f to 0.96f, -1.6f to 0.99f, -2.25f to 0.99f, -2.39f to 0.86f),
        windshieldZ = 0.6f, roofFrontZ = -0.1f, roofRearZ = -0.7f, rearGlassZ = -1.95f, roofY = 1.38f,
        grille = CarStyle.Grille.KIDNEY, headlights = CarStyle.Headlights.ANGULAR, wing = CarStyle.Wing.LIP,
        exhaust = CarStyle.Exhaust.QUAD, taillights = CarStyle.Taillights.L_SHAPE,
        hoodDip = 0.03f, hipRise = 0.05f, frontWidth = 0.97f, cabinBase = 0.84f, cabinTop = 0.62f,
    )
    val MERCEDES = CarStyle(
        "amggt", 4.76f, 1.0f, 1.30f, -1.42f, 0.26f, 0.4f,
        // From Andy's reference photos (AMG ONE hypercar): low nose with big dark openings, cab-forward
        // teardrop cabin with a roof snorkel, a long engine cover with a fin, and a big carbon wing.
        listOf(2.38f to 0.44f, 2.25f to 0.57f, 1.9f to 0.68f, 1.3f to 0.77f, 0.95f to 0.82f, 0f to 0.92f, -0.95f to 1.0f, -1.6f to 0.98f, -2.15f to 0.95f, -2.3f to 0.92f, -2.38f to 0.8f),
        windshieldZ = 0.95f, roofFrontZ = 0.15f, roofRearZ = -0.45f, rearGlassZ = -0.95f, roofY = 1.25f,
        grille = CarStyle.Grille.ONE, headlights = CarStyle.Headlights.SWEPT, wing = CarStyle.Wing.FIXED,
        exhaust = CarStyle.Exhaust.CENTER_PAIR, taillights = CarStyle.Taillights.SLIM_PAIR, sideIntake = true,
        hoodDip = 0.09f, hipRise = 0.08f, frontWidth = 0.94f, cabinBase = 0.68f, cabinTop = 0.46f,
        wingRise = 0.2f, wingZ = 0.26f,
    )
    val AUDI = CarStyle(
        "r8", 4.43f, 0.97f, 1.34f, -1.31f, 0.32f, 0.42f,
        // From reference photos (R8 V10 GT): blunt nose with a wide hexagonal grille, raked windshield,
        // high engine deck, black side blades, a fixed wing on stalks.
        listOf(2.21f to 0.56f, 2.08f to 0.66f, 1.5f to 0.74f, 0.85f to 0.83f, 0.2f to 0.86f, -0.65f to 0.9f, -0.95f to 1.0f, -1.85f to 1.03f, -2.15f to 1.0f, -2.21f to 0.86f),
        windshieldZ = 0.85f, roofFrontZ = 0f, roofRearZ = -0.65f, rearGlassZ = -1.0f, roofY = 1.24f,
        grille = CarStyle.Grille.SINGLEFRAME, headlights = CarStyle.Headlights.SWEPT, wing = CarStyle.Wing.SWAN_NECK,
        exhaust = CarStyle.Exhaust.OVAL_PAIR, taillights = CarStyle.Taillights.SLIM_PAIR, sideBlade = true,
        hoodDip = 0.04f, hipRise = 0.04f, frontWidth = 0.97f, cabinBase = 0.78f, cabinTop = 0.55f,
    )
    val ASTON = CarStyle(
        "vantage", 4.50f, 0.99f, 1.38f, -1.32f, 0.30f, 0.44f,
        // From reference photos: long hood between raised fenders, cabin set back and narrow over
        // broad rear haunches, fastback glass ending in a ducktail.
        listOf(2.25f to 0.6f, 2.12f to 0.68f, 1.5f to 0.8f, 0.35f to 0.9f, -0.25f to 0.92f, -1.0f to 0.94f, -1.75f to 0.97f, -2.1f to 1.01f, -2.2f to 1.0f, -2.25f to 0.86f),
        windshieldZ = 0.3f, roofFrontZ = -0.35f, roofRearZ = -0.9f, rearGlassZ = -1.75f, roofY = 1.27f,
        grille = CarStyle.Grille.ASTON, headlights = CarStyle.Headlights.SWEPT, wing = CarStyle.Wing.NONE,
        exhaust = CarStyle.Exhaust.QUAD, taillights = CarStyle.Taillights.BLADE,
        hoodDip = 0.06f, hipRise = 0.1f, frontWidth = 0.92f, cabinBase = 0.74f, cabinTop = 0.52f,
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
        // The top silhouette as a smooth Catmull-Rom curve through the style's control points.
        val top = st.top
        for (k in 0 until top.size - 1) {
            val p0 = top[maxOf(k - 1, 0)]
            val p1 = top[k]
            val p2 = top[k + 1]
            val p3 = top[minOf(k + 2, top.size - 1)]
            for (step in 0 until 5) {
                val t = step / 5f
                val t2 = t * t
                val t3 = t2 * t
                fun cr(a: Float, b: Float, c: Float, d: Float) =
                    0.5f * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
                pts.add(P(cr(p0.first, p1.first, p2.first, p3.first), cr(p0.second, p1.second, p2.second, p3.second), if (k == 0 && step == 0) 0.7f else 1f))
            }
        }
        pts.add(P(top.last().first, top.last().second, 0.7f))
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

    /** 1 over the front fenders (between the nose and the windshield), fading at both ends. */
    private fun hoodZone(st: CarStyle, z: Float): Float =
        smooth((st.front - 0.05f - z) / 0.35f) * smooth((z - st.windshieldZ + 0.05f) / 0.35f)

    /** 1 over the rear wheels. */
    private fun hipZone(st: CarStyle, z: Float): Float {
        val t = clamp(abs(z - st.rearAxle) / 0.8f, 0f, 1f)
        return 0.5f + 0.5f * cos(t * PI.toFloat())
    }

    fun body(st: CarStyle, paint: Rgb): FloatArray {
        val b = MeshBuilder(1 shl 15)
        val profile = sideProfile(st)
        val hw = st.halfWidth

        // Lofted across the width; upper points are pulled in near the sides for round shoulders.
        // Across the width, denser towards the sides where the body curves most.
        val us = (0..24).map { k -> kotlin.math.sin((k / 24f * 2f - 1f) * PI.toFloat() / 2f) }
        val rings = us.map { u ->
            val s = sqrt(maxOf(0f, 1f - u * u)).coerceAtLeast(0.3f)
            val shrink = 1f - s
            // Fender crowns: 0 on the center line, full height towards the sides, before the shoulder rounds off.
            val crown = smooth((abs(u) - 0.12f) / 0.5f)
            profile.map { p ->
                // Never pull the shoulders below the wheel arches: that leaves fender bulges.
                val rise = (st.hoodDip * hoodZone(st, p.z) + st.hipRise * hipZone(st, p.z)) * crown * p.round
                val y = if (p.round == 0f) p.y else maxOf(p.y - shrink * p.round * 0.22f + rise, archTop(st, p.z))
                val z = p.z * (1f - shrink * p.round * 0.04f)
                // Rounder nose and tail in plan view, narrower front track, tumblehome towards the top and
                // a tuck-under towards the sills.
                val taper = 1f - 0.16f * clamp((z - (st.front - 0.38f)) / 0.38f, 0f, 1f) -
                    0.08f * clamp((st.rear + 0.3f - z) / 0.3f, 0f, 1f)
                val plan = st.frontWidth + (1f - st.frontWidth) * smooth((st.frontAxle - z) / (st.frontAxle - st.rearAxle - 0.3f))
                val tuck = 0.05f * clamp((0.5f - y) / 0.2f, 0f, 1f)
                V3(u * taper * plan * (hw - 0.06f * clamp((y - 0.55f) / 0.35f, 0f, 1f) - tuck), y, z)
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
        tailHousings(b, st)
        when (st.grille) {
            CarStyle.Grille.ASTON -> astonDetails(b, st)
            CarStyle.Grille.LAMBO -> lamboDetails(b, st, paint)
            CarStyle.Grille.PORSCHE -> porscheDetails(b, st)
            CarStyle.Grille.ONE -> oneDetails(b, st, paint)
            else -> Unit
        }
        if (st.grille == CarStyle.Grille.SINGLEFRAME) {
            // R8 rear: a black mesh band across the whole tail under the lights.
            val zr = st.rear - 0.005f
            b.gloss = 0.3f
            plate(b, listOf(-(st.halfWidth - 0.12f) to 0.3f, st.halfWidth - 0.12f to 0.3f, st.halfWidth - 0.1f to 0.62f, -(st.halfWidth - 0.1f) to 0.62f), zr - 0.06f, zr - 0.02f, TRIM)
        }
        // Side mirrors on short stalks at the base of the A-pillars.
        for (side in floatArrayOf(-1f, 1f)) {
            val z = st.windshieldZ - 0.18f
            val y = st.topY(z) + 0.1f
            b.gloss = 0.3f
            b.orientedBox(V3(side * (hw - 0.1f), y - 0.06f, z + 0.02f), V3(0.1f, 0f, 0f), V3(0f, 0.03f, 0f), V3(0f, 0f, 0.035f), paint)
            b.gloss = 0.8f
            b.blob(V3(side * (hw + 0.02f), y, z), 0.09f, 0.055f, 0.065f, 5, 10, paint)
            b.gloss = 0.95f
            b.orientedBox(V3(side * (hw + 0.02f), y, z - 0.06f), V3(0.075f, 0f, 0f), V3(0f, 0.04f, 0f), V3(0f, 0f, 0.008f), GLASS)
        }
        wheelWells(b, st)
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
                // The big black side blade behind the doors, from the sill up to the shoulder.
                b.gloss = 0.6f
                val z0 = st.rearAxle + ARCH_RADIUS + 0.1f
                b.hexahedron(
                    arrayOf(
                        V3(x - side * 0.03f, 0.36f, z0), V3(x + side * 0.012f, 0.36f, z0), V3(x + side * 0.012f, 0.36f, z0 + 0.42f), V3(x - side * 0.03f, 0.36f, z0 + 0.42f),
                        V3(x - side * 0.06f, 0.84f, z0 + 0.06f), V3(x - side * 0.02f, 0.84f, z0 + 0.06f), V3(x - side * 0.02f, 0.84f, z0 + 0.5f), V3(x - side * 0.06f, 0.84f, z0 + 0.5f),
                    ),
                    TRIM,
                )
            }
        }
        b.gloss = 0f
        return b.toArray()
    }

    /** Vantage details from the reference photos: hood vents, fender gills, the big rear diffuser and corner vents. */
    private fun astonDetails(b: MeshBuilder, st: CarStyle) {
        val hw = st.halfWidth
        b.gloss = 0.2f
        for (side in floatArrayOf(-1f, 1f)) {
            // Two vents on the hood ahead of the windshield.
            val zv = st.windshieldZ + 0.45f
            b.orientedBox(V3(side * 0.3f, st.topY(zv) + 0.005f, zv), V3(0.12f, 0f, side * 0.05f), V3(0f, 0.008f, 0f), V3(0f, 0f, 0.035f), TRIM)
            // Gill behind the front wheel.
            val zg = st.frontAxle - ARCH_RADIUS - 0.28f
            b.orientedBox(V3(side * (hw - 0.005f), 0.6f, zg), V3(0.012f, 0f, 0f), V3(0f, 0.018f, 0.004f), V3(0f, 0.01f, 0.18f), TRIM)
            // Tall angled vents at the rear bumper's corners.
            val zr = st.rear + 0.04f
            plateZ(b, listOf(side * (hw - 0.2f) to 0.4f, side * (hw - 0.08f) to 0.42f, side * (hw - 0.05f) to 0.64f, side * (hw - 0.14f) to 0.58f), zr - 0.06f, zr, TRIM)
        }
        // Diffuser: a wide black band framed by a bright strip, with the quad exhausts in it.
        val zr = st.rear - 0.005f
        b.gloss = 0.3f
        plateZ(b, listOf(-(hw - 0.2f) to 0.24f, hw - 0.2f to 0.24f, hw - 0.16f to 0.46f, 0.3f to 0.38f, -0.3f to 0.38f, -(hw - 0.16f) to 0.46f), zr - 0.1f, zr - 0.035f, TRIM)
        b.gloss = 0.9f
        for (side in floatArrayOf(-1f, 1f)) {
            b.orientedBox(V3(side * (hw * 0.5f - 0.05f), 0.43f, zr - 0.03f), V3(hw * 0.36f, side * 0.04f, 0f), V3(0f, 0.01f, 0f), V3(0f, 0f, 0.012f), CHROME.scale(0.8f))
        }
    }

    /** Super Trofeo details: two vents in the hood ahead of the windshield and a scoop on the roof. */
    private fun lamboDetails(b: MeshBuilder, st: CarStyle, paint: Rgb) {
        b.gloss = 0.2f
        for (side in floatArrayOf(-1f, 1f)) {
            val zv = st.windshieldZ + 0.3f
            b.orientedBox(V3(side * 0.22f, st.topY(zv) + 0.004f, zv), V3(0.12f, 0f, 0f), V3(0f, 0.008f, 0f), V3(0f, 0.02f, 0.12f), TRIM)
        }
        roofScoop(b, st, paint, 0.13f)
        // Black diffuser fins under the tail.
        b.gloss = 0.3f
        for (x in floatArrayOf(-0.5f, -0.25f, 0f, 0.25f, 0.5f)) b.box(x, 0.3f, st.rear + 0.14f, 0.01f, 0.07f, 0.16f, CARBON)
    }

    /** GT3 RS details: two big hood vents, an intake slot behind each door and a black diffuser. */
    private fun porscheDetails(b: MeshBuilder, st: CarStyle) {
        val hw = st.halfWidth
        b.gloss = 0.2f
        for (side in floatArrayOf(-1f, 1f)) {
            val zv = st.front - 0.62f
            b.orientedBox(V3(side * 0.2f, st.topY(zv) + 0.01f, zv), V3(0.13f, 0f, 0f), V3(0f, 0.01f, 0.02f), V3(0f, -0.02f, 0.2f), TRIM)
            // Louvers on top of the front fenders.
            for (k in 0..5) {
                val z = st.frontAxle + 0.22f - k * 0.08f
                b.orientedBox(V3(side * (hw - 0.3f), st.topY(z) + st.hoodDip + 0.005f, z), V3(0.13f, 0f, 0f), V3(0f, 0.008f, 0f), V3(0f, 0.01f, 0.02f), TRIM)
            }
            // Slot in the rear fender, just behind the door.
            val zs = st.rearAxle + ARCH_RADIUS + 0.22f
            b.orientedBox(V3(side * (hw - 0.02f), 0.66f, zs), V3(0.03f, 0f, 0f), V3(0f, 0.13f, -0.04f), V3(0f, 0f, 0.05f), TRIM)
        }
        val zr = st.rear - 0.005f
        b.gloss = 0.3f
        plateZ(b, listOf(-(hw - 0.2f) to 0.26f, hw - 0.2f to 0.26f, hw - 0.18f to 0.42f, -(hw - 0.18f) to 0.42f), zr - 0.08f, zr - 0.03f, TRIM)
    }

    /** AMG ONE details: hood vents, louvers over the front wheels, a roof snorkel and a fin down the engine cover. */
    private fun oneDetails(b: MeshBuilder, st: CarStyle, paint: Rgb) {
        val hw = st.halfWidth
        b.gloss = 0.2f
        for (side in floatArrayOf(-1f, 1f)) {
            // Two long vents in the hood.
            val zv = st.front - 0.75f
            b.orientedBox(V3(side * 0.24f, st.topY(zv) + 0.01f, zv), V3(0.12f, 0f, 0f), V3(0f, 0.01f, 0f), V3(0f, -0.02f, 0.28f), TRIM)
            // Louvers on top of the front fenders.
            for (k in 0..4) {
                val z = st.frontAxle + 0.2f - k * 0.1f
                b.orientedBox(V3(side * (hw - 0.3f), st.topY(z) + st.hoodDip + 0.005f, z), V3(0.16f, 0f, 0f), V3(0f, 0.008f, 0f), V3(0f, 0.01f, 0.025f), TRIM)
            }
        }
        roofScoop(b, st, paint, 0.15f)
        // The fin from the roof down the engine cover to the wing.
        val z0 = st.roofRearZ
        val z1 = st.rear + st.wingZ
        val t = 0.012f
        b.gloss = 0.8f
        b.hexahedron(
            arrayOf(
                V3(-t, st.topY(z0) + 0.1f, z0), V3(t, st.topY(z0) + 0.1f, z0), V3(t, st.topY(z1), z1), V3(-t, st.topY(z1), z1),
                V3(-t, st.roofY + 0.02f, z0), V3(t, st.roofY + 0.02f, z0), V3(t, st.topY(z1) + 0.22f, z1), V3(-t, st.topY(z1) + 0.22f, z1),
            ),
            paint,
        )
    }

    /** Air scoop on the roof center: its mouth faces forward, and it runs back over the rear glass. */
    private fun roofScoop(b: MeshBuilder, st: CarStyle, paint: Rgb, height: Float) {
        val zf = (st.roofFrontZ + st.roofRearZ) / 2f + 0.1f
        val zb = st.rearGlassZ + 0.1f
        val yf = st.roofY - 0.02f
        val yb = st.topY(zb) + 0.02f
        val wf = 0.13f
        val wb = 0.08f
        b.gloss = 0.8f
        b.hexahedron(
            arrayOf(
                V3(-wf, yf, zf), V3(wf, yf, zf), V3(wb, yb, zb), V3(-wb, yb, zb),
                V3(-wf, yf + height, zf), V3(wf, yf + height, zf), V3(wb, yb + 0.04f, zb), V3(-wb, yb + 0.04f, zb),
            ),
            paint,
        )
        // The dark mouth.
        b.gloss = 0.1f
        b.orientedBox(V3(0f, yf + height * 0.55f, zf + 0.004f), V3(wf - 0.025f, 0f, 0f), V3(0f, height * 0.35f, 0f), V3(0f, 0f, 0.006f), WELL)
    }

    /** [plate] for faces pointing backwards (polygons given as seen from behind). */
    private fun plateZ(b: MeshBuilder, poly: List<Pair<Float, Float>>, z0: Float, z1: Float, color: Rgb) = plate(b, poly, z0, z1, color)

    /** Dark liners inside the wheel arches, so you can't see through the body above the tires. */
    private fun wheelWells(b: MeshBuilder, st: CarStyle) {
        val r = ARCH_RADIUS + 0.01f
        val hw = st.halfWidth
        b.gloss = 0.05f
        val steps = 12
        for (axle in floatArrayOf(st.frontAxle, st.rearAxle)) for (side in floatArrayOf(-1f, 1f)) {
            val xo = side * (hw - 0.03f)
            val xi = side * (hw - 0.42f)
            val above = V3(xo, 3f, axle)
            for (k in 0 until steps) {
                val a0 = (k * PI / steps).toFloat()
                val a1 = ((k + 1) * PI / steps).toFloat()
                val p0 = V3(0f, WHEEL_RADIUS + sin(a0) * r, axle + cos(a0) * r)
                val p1 = V3(0f, WHEEL_RADIUS + sin(a1) * r, axle + cos(a1) * r)
                // Liner facing down towards the wheel.
                b.quad(V3(xo, p0.y, p0.z), V3(xo, p1.y, p1.z), V3(xi, p1.y, p1.z), V3(xi, p0.y, p0.z), WELL, above)
                // Inner wall facing out.
                b.tri(V3(xi, WHEEL_RADIUS - 0.08f, axle), V3(xi, p0.y, p0.z), V3(xi, p1.y, p1.z), WELL, V3(0f, WHEEL_RADIUS, axle))
            }
            b.quad(
                V3(xi, WHEEL_RADIUS - 0.08f, axle - r), V3(xi, WHEEL_RADIUS - 0.08f, axle + r),
                V3(xi, WHEEL_RADIUS, axle + r), V3(xi, WHEEL_RADIUS, axle - r), WELL, V3(0f, WHEEL_RADIUS, axle),
            )
        }
    }

    /** Glass greenhouse with painted roof and pillars, lofted along z. */
    private fun cabin(b: MeshBuilder, st: CarStyle, paint: Rgb) {
        val hw = st.halfWidth
        val zs = (0..20).map { k -> st.rearGlassZ + (st.windshieldZ - st.rearGlassZ) * k / 20f }
        val segs = 32
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
                val w = hw * (st.cabinBase + (st.cabinTop - st.cabinBase) * t)
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
                // Super Trofeo: huge hexagonal corner intakes, a low center mesh and a wide carbon splitter.
                for (side in floatArrayOf(-1f, 1f)) {
                    plate(b, listOf(side * 0.36f to 0.28f, side * 0.82f to 0.28f, side * 0.88f to 0.4f, side * 0.8f to 0.5f, side * 0.5f to 0.47f, side * 0.4f to 0.4f), zf - 0.22f, zf - 0.03f, TRIM)
                }
                plate(b, listOf(-0.34f to 0.28f, 0.34f to 0.28f, 0.3f to 0.38f, -0.3f to 0.38f), zf - 0.15f, zf - 0.01f, TRIM)
                b.gloss = 0.4f
                b.box(0f, 0.25f, zf - 0.04f, st.halfWidth - 0.04f, 0.018f, 0.12f, CARBON)
            }
            CarStyle.Grille.PORSCHE -> {
                // GT3 RS: one wide black intake across the lower bumper.
                plate(b, listOf(-0.62f to 0.27f, 0.62f to 0.27f, 0.58f to 0.45f, -0.58f to 0.45f), zf - 0.2f, zf - 0.03f, TRIM)
                for (side in floatArrayOf(-1f, 1f)) {
                    plate(b, listOf(side * 0.5f to 0.3f, side * 0.78f to 0.3f, side * 0.74f to 0.42f, side * 0.52f to 0.42f), zf - 0.25f, zf - 0.07f, TRIM)
                }
            }
            CarStyle.Grille.KIDNEY -> {
                // Two huge upright kidneys with bronze surrounds and vertical slats, nearly meeting in the middle.
                fun kidney(cx: Float, w: Float, y0: Float, y1: Float): List<Pair<Float, Float>> {
                    val r = w * 0.45f
                    return listOf(
                        cx - w + r to y0, cx + w - r to y0, cx + w to y0 + r, cx + w to y1 - r,
                        cx + w - r to y1, cx - w + r to y1, cx - w to y1 - r, cx - w to y0 + r,
                    )
                }
                for (side in floatArrayOf(-1f, 1f)) {
                    val x = side * 0.19f
                    b.gloss = 0.9f
                    plate(b, kidney(x, 0.17f, 0.3f, 0.8f), zf - 0.12f, zf + 0.004f, BRONZE)
                    b.gloss = 0.3f
                    plate(b, kidney(x, 0.15f, 0.32f, 0.78f), zf - 0.1f, zf + 0.012f, TRIM)
                    b.gloss = 0.85f
                    for (k in -3..3) {
                        b.orientedBox(V3(x + k * 0.04f, 0.55f, zf + 0.012f), V3(0.006f, 0f, 0f), V3(0f, 0.2f, 0f), V3(0f, 0f, 0.008f), CHROME.scale(0.35f))
                    }
                    // Deep angular intakes below the headlights.
                    b.gloss = 0.3f
                    plate(b, listOf(side * 0.42f to 0.27f, side * 0.86f to 0.27f, side * 0.88f to 0.36f, side * 0.6f to 0.5f, side * 0.42f to 0.42f), zf - 0.3f, zf - 0.06f, TRIM)
                }
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
                // Wide, flat hexagonal grille and big angular corner intakes, all black honeycomb.
                plate(b, listOf(-0.4f to 0.28f, 0.4f to 0.28f, 0.47f to 0.36f, 0.42f to 0.54f, -0.42f to 0.54f, -0.47f to 0.36f), zf - 0.2f, zf - 0.005f, TRIM)
                for (side in floatArrayOf(-1f, 1f)) {
                    plate(b, listOf(side * 0.56f to 0.28f, side * 0.84f to 0.28f, side * 0.86f to 0.4f, side * 0.62f to 0.5f), zf - 0.28f, zf - 0.04f, TRIM)
                    // Canards on the bumper corners.
                    b.gloss = 0.5f
                    b.orientedBox(V3(side * 0.82f, 0.36f, zf - 0.12f), V3(0.08f, side * 0.03f, 0f), V3(0f, 0.006f, 0f), V3(0f, 0f, 0.06f), CARBON)
                    b.gloss = 0.3f
                }
            }
            CarStyle.Grille.ONE -> {
                // AMG ONE: a wide low center intake and big dark openings under the lamps.
                plate(b, listOf(-0.42f to 0.27f, 0.42f to 0.27f, 0.36f to 0.42f, -0.36f to 0.42f), zf - 0.16f, zf - 0.02f, TRIM)
                for (side in floatArrayOf(-1f, 1f)) {
                    plate(b, listOf(side * 0.5f to 0.27f, side * 0.86f to 0.3f, side * 0.84f to 0.5f, side * 0.56f to 0.5f, side * 0.46f to 0.4f), zf - 0.28f, zf - 0.06f, TRIM)
                }
                b.gloss = 0.4f
                b.box(0f, 0.23f, zf - 0.04f, st.halfWidth - 0.08f, 0.015f, 0.1f, CARBON)
            }
            CarStyle.Grille.ASTON -> {
                // The huge grille: widest near the bottom, narrowing to the top, with horizontal slats,
                // and tall dark intakes at the bumper corners.
                val outline = listOf(-0.5f to 0.26f, 0.5f to 0.26f, 0.64f to 0.36f, 0.56f to 0.52f, 0.36f to 0.6f, -0.36f to 0.6f, -0.56f to 0.52f, -0.64f to 0.36f)
                plate(b, outline, zf - 0.18f, zf - 0.005f, TRIM)
                b.gloss = 0.9f
                for (k in 0..6) {
                    val y = 0.3f + k * 0.045f
                    val w = if (y < 0.36f) 0.5f + (y - 0.26f) * 1.4f else 0.64f - (y - 0.36f) * 1.1f
                    b.orientedBox(V3(0f, y, zf + 0.002f), V3(w - 0.04f, 0f, 0f), V3(0f, 0.005f, 0f), V3(0f, 0f, 0.012f), CHROME.scale(0.8f))
                }
                b.gloss = 0.3f
                for (side in floatArrayOf(-1f, 1f)) {
                    plate(b, listOf(side * 0.72f to 0.28f, side * 0.8f to 0.3f, side * 0.84f to 0.56f, side * 0.76f to 0.5f), zf - 0.3f, zf - 0.1f, TRIM)
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
                    // Thin slit lamps above the kidneys' outer edges, angled up towards the fenders.
                    b.orientedBox(V3(side * (hw - 0.33f), y + 0.04f, z - 0.03f), V3(0.2f, side * 0.025f, -side * 0.05f), V3(0f, 0.02f, -0.004f), V3(0f, 0f, 0.05f), LENS)
                    b.orientedBox(V3(side * (hw - 0.33f), y + 0.045f, z + 0.02f), V3(0.19f, side * 0.025f, -side * 0.05f), V3(0f, 0.006f, 0f), V3(0f, 0f, 0.01f), DRL)
                }
                CarStyle.Headlights.SWEPT -> {
                    // Slim lamps set into the fender tops, sweeping up and back from the grille's corners.
                    val z = st.front - 0.36f
                    val y = st.topY(z) + st.hoodDip - 0.05f
                    val dir = V3(side * 0.12f, 0.06f, -0.99f).normalized()
                    val across = V3(1f, -side * 0.35f, 0f).normalized()
                    b.gloss = 1f
                    b.orientedBox(V3(side * (hw - 0.27f), y, z), dir * 0.2f, across * 0.07f * side, V3(0f, 1f, 0.15f).normalized() * 0.02f, LENS)
                    b.orientedBox(V3(side * (hw - 0.22f), y + 0.012f, z - 0.02f), dir * 0.17f, across * 0.01f * side, V3(0f, 1f, 0.15f).normalized() * 0.022f, DRL)
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
                val z = zr + st.wingZ
                val deck = st.topY(z + 0.15f)
                val wingY = deck + if (st.wingRise > 0f) st.wingRise else if (st.wing == CarStyle.Wing.SWAN_NECK) 0.36f else 0.28f
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
                    CARBON,
                )
                for (side in floatArrayOf(-1f, 1f)) b.box(side * (hw - 0.04f), wingY, z - 0.03f, 0.012f, 0.08f, 0.2f, CARBON)
            }
            CarStyle.Wing.NONE -> Unit
        }
    }

    private fun exhaust(b: MeshBuilder, st: CarStyle) {
        val z = st.rear - 0.01f
        val tip = CHROME.scale(0.6f)
        b.gloss = 0.9f
        fun pipe(x: Float, y: Float, r: Float, w: Float = r) = b.blob(V3(x, y, z), w, r, 0.06f, 3, 12, tip)
        when (st.exhaust) {
            CarStyle.Exhaust.CENTER_HIGH -> { pipe(-0.09f, 0.62f, 0.05f); pipe(0.09f, 0.62f, 0.05f) }
            CarStyle.Exhaust.CENTER_PAIR -> { pipe(-0.1f, 0.36f, 0.055f); pipe(0.1f, 0.36f, 0.055f) }
            CarStyle.Exhaust.QUAD -> for (x in floatArrayOf(-0.62f, -0.48f, 0.48f, 0.62f)) pipe(x, 0.33f, 0.05f, 0.06f)
            CarStyle.Exhaust.OVAL_PAIR -> { pipe(-0.55f, 0.36f, 0.04f, 0.09f); pipe(0.55f, 0.36f, 0.04f, 0.09f) }
        }
    }

    /** Dark housings behind the tail lights, so they stand out against any paint color. */
    private fun tailHousings(b: MeshBuilder, st: CarStyle) {
        val lights = tailLights(st, Rgb(0.03f, 0.03f, 0.035f), grow = 0.02f, depth = 0.016f)
        b.append(lights)
    }

    /** Tail lights for [st]: dark red glass normally, glowing while braking. */
    fun tailLights(st: CarStyle): FloatArray = tailLights(st, Rgb(0.42f, 0.015f, 0.02f), 0f, 0.022f)

    private fun tailLights(st: CarStyle, red: Rgb, grow: Float, depth: Float): FloatArray {
        val b = object {
            val m = MeshBuilder()
            fun orientedBox(c: V3, u: V3, v: V3, w: V3, color: Rgb) {
                val ul = u.length()
                val vl = v.length()
                m.orientedBox(c, u * ((ul + grow) / ul), v * ((vl + grow) / vl), w * (depth / w.length()), color)
            }
            var gloss: Float
                get() = m.gloss
                set(value) { m.gloss = value }
            fun toArray() = m.toArray()
        }
        b.gloss = 0.95f
        val z = st.rear - 0.012f
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
                val cx = side * (hw - 0.38f)
                b.orientedBox(V3(cx, y, z), V3(0.17f, 0f, 0f), V3(0f, 0.03f, 0f), V3(0f, 0f, 0.02f), red)
                b.orientedBox(V3(cx + side * 0.14f, y - 0.055f, z), V3(0.03f, 0f, 0f), V3(0f, 0.045f, 0f), V3(0f, 0f, 0.02f), red)
            }
            CarStyle.Taillights.BLADE -> {
                // A thin blade across the ducktail, widening and dipping into the lamps at the corners.
                b.orientedBox(V3(0f, y + 0.03f, z), V3(hw - 0.5f, 0f, 0f), V3(0f, 0.012f, 0f), V3(0f, 0f, 0.02f), red)
                for (side in floatArrayOf(-1f, 1f)) {
                    b.orientedBox(V3(side * (hw - 0.34f), y + 0.01f, z + 0.01f), V3(0.13f, -side * 0.03f, 0f), V3(0f, 0.028f, 0f), V3(0f, 0f, 0.02f), red)
                }
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

    const val WHEEL_RADIUS_RENDER = WHEEL_RADIUS
}
