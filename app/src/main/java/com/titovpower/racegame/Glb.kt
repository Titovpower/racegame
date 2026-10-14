package com.titovpower.racegame

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.pow
import kotlin.math.sqrt

/** How a part of a mesh is shaded (glTF PBR material settings). */
enum class Surfacing {
    /** Derived from each triangle's gloss value: paint, glass, chrome, rubber, matte. */
    AUTO,
    /** Grass and terrain, with a tiling detail texture. */
    GRASS,
    /** Road surface, with a tiling asphalt texture. */
    ASPHALT,
    /** Lamps: emissive, switched on at runtime (brake lights). */
    LAMP,
    /**
     * Grass blades and leaves: thin cards seen from both sides. Written with both windings (the
     * vertices are shared), so each side is a front face with the same upward/outward normal and
     * lights alike; a double-sided material would flip the normal and darken one side.
     */
    FOLIAGE,
}

/** A named mesh part: [MeshBuilder] vertex data plus how to shade it. */
class GlbPart(val name: String, val data: FloatArray, val surfacing: Surfacing = Surfacing.AUTO)

/**
 * Writes binary glTF 2.0 (.glb) from [MeshBuilder] data, so the renderer loads the procedural
 * meshes like any real model, with physically based materials. Each part becomes
 * a node; triangles are grouped into primitives by material. Vertex colors (sRGB in the builder)
 * are converted to linear COLOR_0.
 */
object Glb {
    private class Mat(val key: String, val json: String)

    private class Prim(val mat: Int, val tris: MutableList<Int> = ArrayList())

    fun write(parts: List<GlbPart>): ByteArray {
        val bin = ByteArrayOutputStream()
        val views = ArrayList<String>()
        val accessors = ArrayList<String>()
        val materials = ArrayList<Mat>()
        val images = ArrayList<ByteArray>()
        val textures = ArrayList<String>()
        val meshes = ArrayList<String>()
        val nodes = ArrayList<String>()

        fun view(bytes: ByteArray): Int {
            while (bin.size() % 4 != 0) bin.write(0)
            views.add("""{"buffer":0,"byteOffset":${bin.size()},"byteLength":${bytes.size}}""")
            bin.write(bytes)
            return views.size - 1
        }

        fun texture(kind: Surfacing): Int {
            val name = kind.name
            val idx = textures.indexOfFirst { it.contains("\"name\":\"$name\"") }
            if (idx >= 0) return idx
            images.add(Textures.png(kind))
            textures.add("""{"name":"$name","source":${images.size - 1},"sampler":0}""")
            return textures.size - 1
        }

        fun material(kind: Surfacing, gloss: Float): Int {
            val key = if (kind == Surfacing.AUTO) "g" + (gloss * 100).toInt() else kind.name
            val existing = materials.indexOfFirst { it.key == key }
            if (existing >= 0) return existing
            val json = when (kind) {
                Surfacing.GRASS -> pbr(key, 0f, 0.95f, texture(kind))
                Surfacing.ASPHALT -> pbr(key, 0f, 0.82f, texture(kind))
                Surfacing.LAMP -> pbr(key, 0f, 0.25f, emissive = true)
                Surfacing.FOLIAGE -> pbr(key, 0f, 0.75f)
                Surfacing.AUTO -> when {
                    gloss >= 0.99f -> pbr(key, 0f, 0.04f) // glass and lenses
                    gloss >= 0.85f -> pbr(key, 1f, 0.18f) // chrome and polished metal
                    gloss >= 0.75f -> pbr(key, 0f, 0.38f, clearcoat = true) // car paint
                    else -> pbr(key, 0f, 0.95f - gloss * 0.9f)
                }
            }
            materials.add(Mat(key, json))
            return materials.size - 1
        }

        val F = MeshBuilder.FLOATS_PER_VERTEX
        for (part in parts) {
            val d = part.data
            val vertexCount = d.size / F
            if (vertexCount == 0) continue
            // Group triangles by material.
            val prims = LinkedHashMap<Int, Prim>()
            for (t in 0 until vertexCount / 3) {
                val gloss = d[t * 3 * F + 9]
                val m = material(part.surfacing, gloss)
                prims.getOrPut(m) { Prim(m) }.tris.add(t)
            }
            val primJson = ArrayList<String>()
            for (prim in prims.values) {
                // Shared vertices are written once and referenced by index (grids, smooth surfaces).
                val textured = part.surfacing == Surfacing.GRASS || part.surfacing == Surfacing.ASPHALT
                val uvScale = if (part.surfacing == Surfacing.GRASS) 0.25f else 0.5f
                val unique = HashMap<VertexKey, Int>()
                val order = ArrayList<Int>()
                val bothSides = part.surfacing == Surfacing.FOLIAGE
                val indices = IntArray(prim.tris.size * if (bothSides) 6 else 3)
                var ii = 0
                for (t in prim.tris) {
                    val w = windingOf(d, t)
                    for (k in w) {
                        val o = (t * 3 + k) * F
                        val key = VertexKey(d, o)
                        val idx = unique.getOrPut(key) { order.add(o); order.size - 1 }
                        indices[ii++] = idx
                    }
                    if (bothSides) {
                        indices[ii] = indices[ii - 3]
                        indices[ii + 1] = indices[ii - 1]
                        indices[ii + 2] = indices[ii - 2]
                        ii += 3
                    }
                }
                val n = order.size
                val pos = ByteBuffer.allocate(n * 12).order(ByteOrder.LITTLE_ENDIAN)
                val nor = ByteBuffer.allocate(n * 12).order(ByteOrder.LITTLE_ENDIAN)
                val col = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
                val uv = if (textured) ByteBuffer.allocate(n * 8).order(ByteOrder.LITTLE_ENDIAN) else null
                val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
                val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
                for (o in order) {
                    for (c in 0 until 3) {
                        val v = d[o + c]
                        pos.putFloat(v)
                        if (v < min[c]) min[c] = v
                        if (v > max[c]) max[c] = v
                    }
                    val nx = d[o + 3]
                    val ny = d[o + 4]
                    val nz = d[o + 5]
                    val l = sqrt(nx * nx + ny * ny + nz * nz).let { if (it < 1e-6f) 1f else it }
                    nor.putFloat(nx / l); nor.putFloat(ny / l); nor.putFloat(nz / l)
                    // Linear color as normalized bytes.
                    for (c in 0 until 3) col.put((toLinear(d[o + 6 + c]) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                    col.put(255.toByte())
                    uv?.putFloat(d[o] * uvScale)
                    uv?.putFloat(d[o + 2] * uvScale)
                }
                val ib = ByteBuffer.allocate(indices.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (v in indices) ib.putInt(v)
                val attrs = StringBuilder()
                accessors.add("""{"bufferView":${view(pos.array())},"componentType":5126,"count":$n,"type":"VEC3","min":[${min.joinToString(",")}],"max":[${max.joinToString(",")}]}""")
                attrs.append("\"POSITION\":${accessors.size - 1}")
                accessors.add("""{"bufferView":${view(nor.array())},"componentType":5126,"count":$n,"type":"VEC3"}""")
                attrs.append(",\"NORMAL\":${accessors.size - 1}")
                accessors.add("""{"bufferView":${view(col.array())},"componentType":5121,"normalized":true,"count":$n,"type":"VEC4"}""")
                attrs.append(",\"COLOR_0\":${accessors.size - 1}")
                if (uv != null) {
                    accessors.add("""{"bufferView":${view(uv.array())},"componentType":5126,"count":$n,"type":"VEC2"}""")
                    attrs.append(",\"TEXCOORD_0\":${accessors.size - 1}")
                }
                accessors.add("""{"bufferView":${view(ib.array())},"componentType":5125,"count":${indices.size},"type":"SCALAR"}""")
                val indexAccessor = accessors.size - 1
                primJson.add("""{"attributes":{$attrs},"indices":$indexAccessor,"material":${prim.mat}}""")
            }
            meshes.add("""{"name":"${part.name}","primitives":[${primJson.joinToString(",")}]}""")
            nodes.add("""{"name":"${part.name}","mesh":${meshes.size - 1}}""")
        }
        val imageJson = images.map { """{"bufferView":${view(it)},"mimeType":"image/png"}""" }

        val json = StringBuilder()
        json.append("""{"asset":{"version":"2.0","generator":"racegame"},""")
        json.append(""""extensionsUsed":["KHR_materials_clearcoat"],""")
        json.append(""""scene":0,"scenes":[{"nodes":[${nodes.indices.joinToString(",")}]}],""")
        json.append(""""nodes":[${nodes.joinToString(",")}],"meshes":[${meshes.joinToString(",")}],""")
        json.append(""""materials":[${materials.joinToString(",") { it.json }}],""")
        if (images.isNotEmpty()) {
            json.append(""""images":[${imageJson.joinToString(",")}],"textures":[${textures.joinToString(",")}],""")
            json.append(""""samplers":[{"magFilter":9729,"minFilter":9987,"wrapS":10497,"wrapT":10497}],""")
        }
        while (bin.size() % 4 != 0) bin.write(0)
        json.append(""""buffers":[{"byteLength":${bin.size()}}],"bufferViews":[${views.joinToString(",")}],""")
        json.append(""""accessors":[${accessors.joinToString(",")}]}""")
        return pack(json.toString(), bin.toByteArray())
    }

    private val CCW = intArrayOf(0, 1, 2)
    private val CW = intArrayOf(0, 2, 1)

    /**
     * Vertex order for triangle [t] so that it winds counter-clockwise around its normals:
     * the builder doesn't care about winding, but the engine culls back faces.
     */
    private fun windingOf(d: FloatArray, t: Int): IntArray {
        val F = MeshBuilder.FLOATS_PER_VERTEX
        val a = t * 3 * F
        val b = a + F
        val c = b + F
        val ux = d[b] - d[a]; val uy = d[b + 1] - d[a + 1]; val uz = d[b + 2] - d[a + 2]
        val vx = d[c] - d[a]; val vy = d[c + 1] - d[a + 1]; val vz = d[c + 2] - d[a + 2]
        val gx = uy * vz - uz * vy
        val gy = uz * vx - ux * vz
        val gz = ux * vy - uy * vx
        val nx = d[a + 3] + d[b + 3] + d[c + 3]
        val ny = d[a + 4] + d[b + 4] + d[c + 4]
        val nz = d[a + 5] + d[b + 5] + d[c + 5]
        return if (gx * nx + gy * ny + gz * nz >= 0f) CCW else CW
    }

    private fun pbr(name: String, metallic: Float, roughness: Float, texture: Int = -1, clearcoat: Boolean = false, emissive: Boolean = false, doubleSided: Boolean = false): String {
        val tex = if (texture >= 0) ""","baseColorTexture":{"index":$texture}""" else ""
        val ext = ArrayList<String>()
        if (clearcoat) ext.add(""""KHR_materials_clearcoat":{"clearcoatFactor":1.0,"clearcoatRoughnessFactor":0.06}""")
        val extJson = if (ext.isEmpty()) "" else ""","extensions":{${ext.joinToString(",")}}"""
        val emissiveJson = if (emissive) ""","emissiveFactor":[0,0,0]""" else ""
        val sides = if (doubleSided) ""","doubleSided":true""" else ""
        return """{"name":"$name","pbrMetallicRoughness":{"baseColorFactor":[1,1,1,1],"metallicFactor":$metallic,"roughnessFactor":$roughness$tex}$emissiveJson$extJson$sides}"""
    }

    private fun toLinear(c: Float) = clamp(c, 0f, 1f).toDouble().pow(2.2).toFloat()

    private fun pack(json: String, bin: ByteArray): ByteArray {
        var js = json.toByteArray(Charsets.UTF_8)
        if (js.size % 4 != 0) js += ByteArray(4 - js.size % 4) { ' '.code.toByte() }
        val total = 12 + 8 + js.size + 8 + bin.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(total)
        out.putInt(js.size).putInt(0x4E4F534A).put(js)
        out.putInt(bin.size).putInt(0x004E4942).put(bin)
        return out.array()
    }
}

/** Identity of one vertex in [MeshBuilder] data (all of its floats), for de-duplication. */
private class VertexKey(private val d: FloatArray, private val o: Int) {
    private val hash: Int = run {
        var h = 17
        for (k in 0 until MeshBuilder.FLOATS_PER_VERTEX) h = h * 31 + d[o + k].toRawBits()
        h
    }

    override fun hashCode() = hash

    override fun equals(other: Any?): Boolean {
        if (other !is VertexKey) return false
        for (k in 0 until MeshBuilder.FLOATS_PER_VERTEX) if (d[o + k] != other.d[other.o + k]) return false
        return true
    }
}

/** Procedural tiling detail textures (mean brightness ~1 so they modulate vertex colors), as PNG. */
object Textures {
    private const val SIZE = 256

    fun png(kind: Surfacing): ByteArray {
        val px = ByteArray(SIZE * SIZE * 3)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val u = x.toFloat() / SIZE
            val v = y.toFloat() / SIZE
            val (r, g, b) = when (kind) {
                Surfacing.GRASS -> {
                    // Blades: fine high-frequency streaks over softer patches.
                    val patch = tile(u, v, 4) * 0.5f + tile(u, v, 8) * 0.3f
                    val blade = tile(u * 1f, v, 64) * 0.6f + tile(u, v, 32) * 0.4f
                    val l = 0.62f + patch * 0.25f + blade * 0.32f
                    Triple(l * (0.95f + 0.1f * tile(u, v, 6)), l, l * 0.9f)
                }
                Surfacing.ASPHALT -> {
                    val grain = tile(u, v, 128) * 0.55f + tile(u, v, 64) * 0.3f + tile(u, v, 16) * 0.15f
                    val stone = if (tile(u, v, 96) > 0.82f) 0.25f else 0f
                    val l = 0.72f + grain * 0.45f + stone
                    Triple(l, l, l * 1.02f)
                }
                else -> Triple(1f, 1f, 1f)
            }
            val o = (y * SIZE + x) * 3
            px[o] = (clamp(r, 0f, 1f) * 255).toInt().toByte()
            px[o + 1] = (clamp(g, 0f, 1f) * 255).toInt().toByte()
            px[o + 2] = (clamp(b, 0f, 1f) * 255).toInt().toByte()
        }
        return encodePng(px, SIZE, SIZE)
    }

    /** Tileable value noise with [cells] cells across the texture. */
    private fun tile(u: Float, v: Float, cells: Int): Float {
        val x = u * cells
        val y = v * cells
        val ix = x.toInt()
        val iy = y.toInt()
        val fx = x - ix
        val fy = y - iy
        fun h(a: Int, b: Int): Float {
            var k = (Math.floorMod(a, cells)) * 374761393 + (Math.floorMod(b, cells)) * 668265263 + cells * 977
            k = (k xor (k ushr 13)) * 1274126177
            return ((k xor (k ushr 16)) and 0xFFFF) / 65535f
        }
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val a = h(ix, iy) + (h(ix + 1, iy) - h(ix, iy)) * sx
        val b = h(ix, iy + 1) + (h(ix + 1, iy + 1) - h(ix, iy + 1)) * sx
        return a + (b - a) * sy
    }

    fun encodePng(rgb: ByteArray, w: Int, h: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until h) {
            raw.write(0)
            raw.write(rgb, y * w * 3, w * 3)
        }
        val deflater = Deflater(6)
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(65536)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            val len = ByteBuffer.allocate(4).putInt(data.size).array()
            out.write(len)
            val td = type.toByteArray() + data
            out.write(td)
            val crc = CRC32()
            crc.update(td)
            out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(2).put(0).put(0).put(0).array())
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
