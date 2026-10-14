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

    val trees = ArrayList<Tree>()
    val rocks = ArrayList<Rock>()

    /** Trees close enough to the drivable area to be hit by the car. */
    val solidTrees = ArrayList<Tree>()

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
            val tree = Tree(x, z, heightAt(x, z, d), 0.8f + rnd.nextFloat() * 0.9f, rnd.nextFloat() < 0.65f)
            trees.add(tree)
            if (d < driveRadius + 3f) solidTrees.add(tree)
        }
        while (rocks.size < 160) {
            val x = (rnd.nextFloat() * 2f - 1f) * (groundExtent - 50f)
            val z = (rnd.nextFloat() * 2f - 1f) * (groundExtent - 50f)
            val d = track.nearest(x, z).distance
            if (d < flatRadius) continue
            rocks.add(Rock(x, z, heightAt(x, z, d), 1f + rnd.nextFloat() * 3f))
        }
    }

    /** Ground height; pass the distance to the track if already known. */
    fun heightAt(x: Float, z: Float, distToTrack: Float = track.nearest(x, z).distance): Float {
        if (distToTrack <= flatRadius) return 0f
        val rise = minOf((distToTrack - flatRadius).pow(1.15f) * 0.22f, 120f)
        return rise * (0.45f + Noise.fbm(x * 0.008f + 3f, z * 0.008f - 5f))
    }
}
