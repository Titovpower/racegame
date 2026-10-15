package com.titovpower.racegame

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A glTF metallic-roughness material, as the renderer uses it (colors linear). */
class GltfMaterial(
    val name: String,
    val baseColor: FloatArray,
    val metallic: Float,
    val roughness: Float,
    val emissive: FloatArray,
    val clearcoat: Float,
    val clearcoatRoughness: Float,
    /** Index into [GltfModel.images], or -1. */
    val image: Int,
    val doubleSided: Boolean,
    val blend: Boolean,
)

/** One triangle list with its node's transform (relative to the model's origin). */
class GltfPrimitive(
    val node: String,
    val matrix: FloatArray,
    val material: Int,
    val positions: FloatArray,
    val normals: FloatArray,
    /** Linear RGBA per vertex, or null (white). */
    val colors: FloatArray?,
    val uvs: FloatArray?,
    val indices: IntArray,
)

class GltfModel(val primitives: List<GltfPrimitive>, val materials: List<GltfMaterial>, val images: List<ByteArray>)

/**
 * Reads binary glTF 2.0 (.glb): triangle meshes with positions, normals, vertex colors and one
 * texture coordinate set, the node hierarchy, and metallic-roughness materials with base color
 * textures, emission and clear coat. Enough for the generated assets and typical car models;
 * skins, morph targets and the other texture maps are ignored.
 */
object Gltf {
    fun parse(glb: ByteArray): GltfModel {
        val buf = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.getInt(0) == 0x46546C67) { "Not a binary glTF file" }
        var off = 12
        var json: Map<*, *>? = null
        var binOffset = 0
        while (off + 8 <= glb.size) {
            val len = buf.getInt(off)
            val type = buf.getInt(off + 4)
            if (type == 0x4E4F534A) json = Json.parse(String(glb, off + 8, len, Charsets.UTF_8)) as Map<*, *>
            if (type == 0x004E4942) binOffset = off + 8
            off += 8 + len
        }
        val root = json ?: error("glTF without JSON")
        val views = root.list("bufferViews")
        val accessors = root.list("accessors")

        fun viewStart(v: Int) = binOffset + views[v].int("byteOffset", 0)

        /** Accessor [a] as floats (normalized integers become 0..1), [comps] per element. */
        fun floats(a: Int): Pair<FloatArray, Int> {
            val acc = accessors[a]
            val count = acc.int("count", 0)
            val comps = when (acc["type"]) { "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4" -> 4; "MAT4" -> 16; else -> 1 }
            val ct = acc.int("componentType", 5126)
            val size = when (ct) { 5120, 5121 -> 1; 5122, 5123 -> 2; else -> 4 }
            val out = FloatArray(count * comps)
            val v = acc["bufferView"] ?: return out to comps
            val vi = (v as Number).toInt()
            val stride = views[vi].int("byteStride", 0).let { if (it == 0) comps * size else it }
            val base = viewStart(vi) + acc.int("byteOffset", 0)
            val norm = acc["normalized"] == true
            for (i in 0 until count) for (c in 0 until comps) {
                val p = base + i * stride + c * size
                out[i * comps + c] = when (ct) {
                    5126 -> buf.getFloat(p)
                    5121 -> (glb[p].toInt() and 0xFF).let { if (norm) it / 255f else it.toFloat() }
                    5123 -> (buf.getShort(p).toInt() and 0xFFFF).let { if (norm) it / 65535f else it.toFloat() }
                    5120 -> glb[p].toInt().let { if (norm) maxOf(it / 127f, -1f) else it.toFloat() }
                    5122 -> buf.getShort(p).toInt().let { if (norm) maxOf(it / 32767f, -1f) else it.toFloat() }
                    else -> (buf.getInt(p).toLong() and 0xFFFFFFFFL).toFloat()
                }
            }
            return out to comps
        }

        fun indices(a: Int): IntArray {
            val acc = accessors[a]
            val count = acc.int("count", 0)
            val ct = acc.int("componentType", 5125)
            val base = viewStart(acc.int("bufferView", 0)) + acc.int("byteOffset", 0)
            return IntArray(count) { i ->
                when (ct) {
                    5121 -> glb[base + i].toInt() and 0xFF
                    5123 -> buf.getShort(base + i * 2).toInt() and 0xFFFF
                    else -> buf.getInt(base + i * 4)
                }
            }
        }

        val images = root.list("images").map { img ->
            val v = img.int("bufferView", -1)
            if (v < 0) ByteArray(0) else viewStart(v).let { s -> glb.copyOfRange(s, s + views[v].int("byteLength", 0)) }
        }
        val textures = root.list("textures")
        val materials = root.list("materials").mapIndexed { k, m ->
            val pbr = m["pbrMetallicRoughness"] as? Map<*, *> ?: emptyMap<String, Any>()
            val cc = (m["extensions"] as? Map<*, *>)?.get("KHR_materials_clearcoat") as? Map<*, *>
            val tex = (pbr["baseColorTexture"] as? Map<*, *>)?.int("index", -1) ?: -1
            GltfMaterial(
                name = m["name"] as? String ?: "material$k",
                baseColor = pbr.floats("baseColorFactor", floatArrayOf(1f, 1f, 1f, 1f)),
                metallic = pbr.float("metallicFactor", 1f),
                roughness = pbr.float("roughnessFactor", 1f),
                emissive = m.floats("emissiveFactor", floatArrayOf(0f, 0f, 0f)),
                clearcoat = cc?.float("clearcoatFactor", 0f) ?: 0f,
                clearcoatRoughness = cc?.float("clearcoatRoughnessFactor", 0f) ?: 0f,
                image = if (tex >= 0 && tex < textures.size) textures[tex].int("source", -1) else -1,
                doubleSided = m["doubleSided"] == true,
                blend = m["alphaMode"] == "BLEND",
            )
        }.ifEmpty {
            listOf(GltfMaterial("default", floatArrayOf(0.8f, 0.8f, 0.8f, 1f), 0f, 0.6f, floatArrayOf(0f, 0f, 0f), 0f, 0f, -1, false, false))
        }

        val meshes = root.list("meshes")
        val nodes = root.list("nodes")
        val prims = ArrayList<GltfPrimitive>()
        fun visit(n: Int, parent: FloatArray) {
            val node = nodes[n]
            val world = multiply(parent, localMatrix(node))
            val mesh = node["mesh"]
            if (mesh != null) for (p in meshes[(mesh as Number).toInt()].list("primitives")) {
                if (p.int("mode", 4) != 4) continue
                val attrs = p["attributes"] as Map<*, *>
                val pos = floats((attrs["POSITION"] as Number).toInt()).first
                val idx = if (p["indices"] != null) indices(p.int("indices", 0)) else IntArray(pos.size / 3) { it }
                val nor = (attrs["NORMAL"] as? Number)?.let { floats(it.toInt()).first } ?: smoothNormals(pos, idx)
                val col = (attrs["COLOR_0"] as? Number)?.let { a ->
                    val (c, comps) = floats(a.toInt())
                    if (comps == 4) c else FloatArray(c.size / 3 * 4) { i -> if (i % 4 == 3) 1f else c[i / 4 * 3 + i % 4] }
                }
                val uv = (attrs["TEXCOORD_0"] as? Number)?.let { floats(it.toInt()).first }
                prims.add(GltfPrimitive(node["name"] as? String ?: "", world, p.int("material", 0).coerceIn(0, materials.size - 1), pos, nor, col, uv, idx))
            }
            for (c in node.ints("children")) visit(c, world)
        }
        val identity = FloatArray(16) { if (it % 5 == 0) 1f else 0f }
        val scenes = root.list("scenes")
        val roots: List<Int> = if (scenes.isNotEmpty()) {
            scenes[(root["scene"] as? Number)?.toInt() ?: 0].ints("nodes")
        } else {
            nodes.indices.toList()
        }
        for (r in roots) visit(r, identity)
        return GltfModel(prims, materials, images)
    }

    /** a x b, column-major (plain Kotlin, so the loader also runs in unit tests). */
    private fun multiply(a: FloatArray, b: FloatArray) = FloatArray(16) { i ->
        val col = i / 4
        val row = i % 4
        var sum = 0f
        for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
        sum
    }

    private fun localMatrix(node: Map<*, *>): FloatArray {
        val m = FloatArray(16)
        val given = node["matrix"] as? List<*>
        if (given != null) {
            for (i in 0 until 16) m[i] = (given[i] as Number).toFloat()
            return m
        }
        val t = node.floats("translation", floatArrayOf(0f, 0f, 0f))
        val r = node.floats("rotation", floatArrayOf(0f, 0f, 0f, 1f))
        val s = node.floats("scale", floatArrayOf(1f, 1f, 1f))
        val (x, y, z, w) = listOf(r[0], r[1], r[2], r[3])
        // Column-major rotation from the quaternion, scaled per axis, then translated.
        m[0] = (1 - 2 * (y * y + z * z)) * s[0]; m[1] = 2 * (x * y + z * w) * s[0]; m[2] = 2 * (x * z - y * w) * s[0]
        m[4] = 2 * (x * y - z * w) * s[1]; m[5] = (1 - 2 * (x * x + z * z)) * s[1]; m[6] = 2 * (y * z + x * w) * s[1]
        m[8] = 2 * (x * z + y * w) * s[2]; m[9] = 2 * (y * z - x * w) * s[2]; m[10] = (1 - 2 * (x * x + y * y)) * s[2]
        m[12] = t[0]; m[13] = t[1]; m[14] = t[2]; m[15] = 1f
        return m
    }

    private fun smoothNormals(pos: FloatArray, idx: IntArray): FloatArray {
        val n = FloatArray(pos.size)
        for (t in 0 until idx.size / 3) {
            val a = idx[t * 3] * 3
            val b = idx[t * 3 + 1] * 3
            val c = idx[t * 3 + 2] * 3
            val ux = pos[b] - pos[a]; val uy = pos[b + 1] - pos[a + 1]; val uz = pos[b + 2] - pos[a + 2]
            val vx = pos[c] - pos[a]; val vy = pos[c + 1] - pos[a + 1]; val vz = pos[c + 2] - pos[a + 2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            for (k in intArrayOf(a, b, c)) { n[k] += nx; n[k + 1] += ny; n[k + 2] += nz }
        }
        return n
    }

    private fun Map<*, *>.list(key: String): List<Map<*, *>> = (this[key] as? List<*>)?.map { it as? Map<*, *> ?: emptyMap<String, Any>() } ?: emptyList()

    private fun Map<*, *>.ints(key: String): List<Int> = (this[key] as? List<*>)?.map { (it as Number).toInt() } ?: emptyList()

    private fun Map<*, *>.int(key: String, default: Int) = (this[key] as? Number)?.toInt() ?: default

    private fun Map<*, *>.float(key: String, default: Float) = (this[key] as? Number)?.toFloat() ?: default

    private fun Map<*, *>.floats(key: String, default: FloatArray): FloatArray =
        (this[key] as? List<*>)?.map { (it as Number).toFloat() }?.toFloatArray() ?: default
}

/** Minimal JSON reader: objects become maps, arrays lists, numbers doubles. */
object Json {
    fun parse(s: String): Any? = Reader(s).run { value().also { skipWs() } }

    private class Reader(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            return when (val c = s[i]) {
                '{' -> {
                    i++
                    val m = LinkedHashMap<String, Any?>()
                    skipWs()
                    if (s[i] == '}') { i++; return m }
                    while (true) {
                        skipWs()
                        val k = string()
                        skipWs(); i++ // ':'
                        m[k] = value()
                        skipWs()
                        if (s[i++] == '}') return m
                    }
                }
                '[' -> {
                    i++
                    val l = ArrayList<Any?>()
                    skipWs()
                    if (s[i] == ']') { i++; return l }
                    while (true) {
                        l.add(value())
                        skipWs()
                        if (s[i++] == ']') return l
                    }
                }
                '"' -> string()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                    if (start == i) error("Unexpected '$c' in JSON at $i")
                    s.substring(start, i).toDouble()
                }
            }
        }

        fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
    }
}
