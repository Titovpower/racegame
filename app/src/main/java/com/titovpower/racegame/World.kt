package com.titovpower.racegame

import kotlin.math.pow
import kotlin.random.Random

/**
 * Static world layout: the track, the flat grass area around it, the hills beyond,
 * and where trees and rocks stand. Pure Kotlin so it can be unit tested.
 */
class World(seed: Int = 7) {
    val track = Track()

    /** Ground is flat (y = 0) within this distance of the track centerline. */
    val flatRadius = 60f

    /** The car is kept within this distance of the track centerline. */
    val driveRadius = 55f

    /** Half the side of the square ground mesh. */
    val groundExtent = 800f

    class Tree(val x: Float, val z: Float, val y: Float, val scale: Float, val pine: Boolean) {
        /** Trunk radius used for collisions. */
        val radius get() = 0.45f * scale
    }

    class Rock(val x: Float, val z: Float, val y: Float, val size: Float)

    class Bush(val x: Float, val z: Float, val size: Float)

    /** A solid circle the car collides with. */
    class Obstacle(val x: Float, val z: Float, val radius: Float)

    val trees = ArrayList<Tree>()
    val rocks = ArrayList<Rock>()
    val bushes = ArrayList<Bush>()

    /** Tree trunks and buildings close enough to the drivable area to be hit by the car. */
    val obstacles = ArrayList<Obstacle>()

    /** Grandstand beside the start straight: center of its front edge, facing the track. */
    val grandstandIndex = 18
    val grandstandLength = 36f
    val grandstandDepth = 8f
    val grandstandOffset = track.halfWidth + track.curbWidth + 10f

    init {
        val rnd = Random(seed)
        val minFromRoad = track.halfWidth + track.curbWidth + 5f
        var attempts = 0
        while (trees.size < 1100 && attempts < 20000) {
            attempts++
            val x = (rnd.nextFloat() * 2f - 1f) * (groundExtent - 50f)
            val z = (rnd.nextFloat() * 2f - 1f) * (groundExtent - 50f)
            val d = track.nearest(x, z).distance
            if (d < minFromRoad) continue
            // Woods get denser away from the track, with clearings driven by noise.
            val density = Noise.fbm(x * 0.012f, z * 0.012f)
            if (density < 0.38f) continue
            if (d < 25f && rnd.nextFloat() < 0.6f) continue
            if (nearGrandstand(x, z)) continue
            val tree = Tree(x, z, heightAt(x, z, d), 0.8f + rnd.nextFloat() * 0.9f, rnd.nextFloat() < 0.62f)
            trees.add(tree)
            if (d < driveRadius + 3f) obstacles.add(Obstacle(x, z, tree.radius))
        }
        while (bushes.size < 500) {
            val x = (rnd.nextFloat() * 2f - 1f) * 450f
            val z = (rnd.nextFloat() * 2f - 1f) * 450f
            val d = track.nearest(x, z).distance
            if (d < minFromRoad + 1f || d > flatRadius) continue
            bushes.add(Bush(x, z, 0.5f + rnd.nextFloat() * 0.9f))
        }
        // The grandstand blocks the car along its whole footprint.
        val (gx, gz) = grandstandCenter()
        val t = track.tangent(grandstandIndex)
        val left = V3(t.z, 0f, -t.x)
        val r = grandstandDepth / 2f
        var along = -grandstandLength / 2f + r
        while (along <= grandstandLength / 2f - r + 0.01f) {
            obstacles.add(Obstacle(gx + t.x * along + left.x * r, gz + t.z * along + left.z * r, r))
            along += r
        }
        while (rocks.size < 160) {
            val x = (rnd.nextFloat() * 2f - 1f) * (groundExtent - 50f)
            val z = (rnd.nextFloat() * 2f - 1f) * (groundExtent - 50f)
            val d = track.nearest(x, z).distance
            if (d < flatRadius) continue
            rocks.add(Rock(x, z, heightAt(x, z, d), 1f + rnd.nextFloat() * 3f))
        }
    }

    /** Front-center of the grandstand on the ground, on the left of the direction of travel. */
    fun grandstandCenter(): Pair<Float, Float> {
        val i = grandstandIndex
        val t = track.tangent(i)
        return (track.xs[i] + t.z * grandstandOffset) to (track.zs[i] - t.x * grandstandOffset)
    }

    private fun nearGrandstand(x: Float, z: Float): Boolean {
        val (gx, gz) = grandstandCenter()
        val dx = x - gx
        val dz = z - gz
        return dx * dx + dz * dz < (grandstandLength * 0.75f) * (grandstandLength * 0.75f)
    }

    /** Ground height; pass the distance to the track if already known. */
    fun heightAt(x: Float, z: Float, distToTrack: Float = track.nearest(x, z).distance): Float {
        if (distToTrack <= flatRadius) return 0f
        val rise = minOf((distToTrack - flatRadius).pow(1.15f) * 0.22f, 120f)
        return rise * (0.45f + Noise.fbm(x * 0.008f + 3f, z * 0.008f - 5f))
    }
}
