#!/usr/bin/env python3
"""Turns a car model exported from Blender (or any glTF tool) into the game's car format.

    python3 tools/models/prepare_car.py in.glb out.glb [--nose -z|+z]

The game wants the nose towards +Z, Y up, meters, origin on the ground under the car. This
script turns the model round when its nose points to -Z, bakes every node transform into the
vertices and merges all parts that share a material, so a model of a thousand parts draws in a
dozen calls. Four groups are kept as separate nodes so the game can move them:

  wheel_0 .. wheel_3   front-left, front-right, rear-left, rear-right, origin at the hub
                       (found as nodes named like "Front wheel L"; the game spins and steers them)
                       (brake calipers stay on the body)
  lights               parts whose material name contains "light bar", "tail" or "brake light"
                       (they glow when the driver brakes)

Materials are copied unchanged, so a material with "paint" in its name still gets the driver's
color. Texture coordinates and textures are dropped (the game renders flat-shaded materials).
"""
import json
import re
import struct
import sys

import numpy as np

COMPONENTS = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}
DTYPES = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
WHEEL = re.compile(r"(front|rear)\s*wheel\s*(l|r|left|right)\b", re.I)
LIGHTS = re.compile(r"light bar|tail|brake light", re.I)
# Parts of a wheel assembly that stay put while the wheel spins.
FIXED = re.compile(r"caliper", re.I)
# The game lifts the body 4 cm (Scene.cars) so the generated body clears its wheels; model wheels
# are children of the body, so they are lowered by the same amount to stay on the ground.
BODY_LIFT = 0.04


def read_glb(path):
    data = open(path, "rb").read()
    magic, _, _ = struct.unpack_from("<III", data, 0)
    assert magic == 0x46546C67, "not a .glb file"
    offset, gltf, binary = 12, None, b""
    while offset < len(data):
        length, kind = struct.unpack_from("<II", data, offset)
        chunk = data[offset + 8: offset + 8 + length]
        if kind == 0x4E4F534A:
            gltf = json.loads(chunk)
        elif kind == 0x004E4942:
            binary = chunk
        offset += 8 + length
    return gltf, binary


def accessor(gltf, binary, index):
    a = gltf["accessors"][index]
    view = gltf["bufferViews"][a["bufferView"]]
    n = COMPONENTS[a["type"]]
    dtype = np.dtype(DTYPES[a["componentType"]])
    start = view.get("byteOffset", 0) + a.get("byteOffset", 0)
    stride = view.get("byteStride", n * dtype.itemsize)
    rows = np.ndarray((a["count"], n), dtype, binary, start, (stride, dtype.itemsize))
    return rows.astype(np.float64 if dtype == np.float32 else np.int64)


def local_matrix(node):
    if "matrix" in node:
        return np.array(node["matrix"], dtype=np.float64).reshape(4, 4).T
    x, y, z, w = node.get("rotation", [0, 0, 0, 1])
    r = np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])
    m = np.eye(4)
    m[:3, :3] = r * np.array(node.get("scale", [1, 1, 1]))
    m[:3, 3] = node.get("translation", [0, 0, 0])
    return m


def main():
    args = sys.argv[1:]
    nose = "-z"
    if "--nose" in args:
        i = args.index("--nose")
        nose = args[i + 1]
        del args[i:i + 2]
    src, dst = args
    gltf, binary = read_glb(src)
    turn = np.diag([-1.0, 1, -1, 1]) if nose == "-z" else np.eye(4)

    # group -> material -> list of (positions, normals, indices)
    groups = {}
    wheel_rest = {}

    def add(group, material, matrix, prim):
        pos = accessor(gltf, binary, prim["attributes"]["POSITION"])
        nrm = accessor(gltf, binary, prim["attributes"]["NORMAL"]) if "NORMAL" in prim["attributes"] else None
        idx = accessor(gltf, binary, prim["indices"]).ravel() if "indices" in prim else np.arange(len(pos))
        assert prim.get("mode", 4) == 4, "only triangle lists are supported"
        p = pos @ matrix[:3, :3].T + matrix[:3, 3]
        if nrm is None:
            nrm = np.zeros_like(pos)
        n = nrm @ np.linalg.inv(matrix[:3, :3])
        n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
        tri = idx.reshape(-1, 3)
        if np.linalg.det(matrix[:3, :3]) < 0:
            tri = tri[:, ::-1]
        groups.setdefault(group, {}).setdefault(material, []).append((p, n, tri))

    def walk(i, parent, group, group_origin):
        node = gltf["nodes"][i]
        world = parent @ local_matrix(node)
        name = node.get("name", "")
        match = WHEEL.search(name) if group is None else None
        if match:
            hub = turn @ world
            x = hub[0, 3]
            k = (0 if match.group(1).lower() == "front" else 2) + (0 if x > 0 else 1)
            group = "wheel_%d" % k
            # Hub frame: the turned car's axes, at the hub (the wheel spins about its X axis).
            group_origin = np.eye(4)
            group_origin[:3, 3] = hub[:3, 3]
            wheel_rest[group] = hub[:3, 3] - [0, BODY_LIFT, 0]
        if "mesh" in node:
            for prim in gltf["meshes"][node["mesh"]]["primitives"]:
                material = prim.get("material", -1)
                mname = gltf["materials"][material].get("name", "") if material >= 0 else ""
                g = group
                m = turn @ world
                if g is not None and FIXED.search(mname):
                    # Kept on the body, lowered like the wheels.
                    g = "body"
                    m = np.array([[1, 0, 0, 0], [0, 1, 0, -BODY_LIFT], [0, 0, 1, 0], [0, 0, 0, 1.0]]) @ m
                elif g is None:
                    g = "lights" if LIGHTS.search(mname) else "body"
                elif group_origin is not None:
                    m = np.linalg.inv(group_origin) @ m
                add(g, material, m, prim)
        for c in node.get("children", []):
            walk(c, world, group, group_origin)

    scene = gltf["scenes"][gltf.get("scene", 0)]
    for root in scene["nodes"]:
        walk(root, np.eye(4), None, None)

    out = {"asset": {"version": "2.0", "generator": "racegame prepare_car.py"},
           "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"name": "car", "children": []}],
           "meshes": [], "accessors": [], "bufferViews": [], "buffers": [],
           "materials": [{k: v for k, v in m.items() if k not in ("normalTexture", "occlusionTexture")}
                         for m in gltf.get("materials", [])]}
    for m in out["materials"]:
        pbr = m.get("pbrMetallicRoughness", {})
        pbr.pop("baseColorTexture", None)
        pbr.pop("metallicRoughnessTexture", None)
        m.pop("emissiveTexture", None)
    if "extensionsUsed" in gltf:
        out["extensionsUsed"] = [e for e in gltf["extensionsUsed"] if e.startswith("KHR_materials")]
    blob = bytearray()

    def put(array, target, kind, ctype):
        while len(blob) % 4:
            blob.append(0)
        view = {"buffer": 0, "byteOffset": len(blob), "byteLength": array.nbytes, "target": target}
        blob.extend(array.tobytes())
        out["bufferViews"].append(view)
        acc = {"bufferView": len(out["bufferViews"]) - 1, "componentType": ctype,
               "count": len(array), "type": kind}
        if kind == "VEC3" and target == 34962:
            acc["min"] = array.min(0).tolist()
            acc["max"] = array.max(0).tolist()
        out["accessors"].append(acc)
        return len(out["accessors"]) - 1

    stats = []
    for group in sorted(groups):
        prims = []
        for material, parts in groups[group].items():
            base, ps, ns, ts = 0, [], [], []
            for p, n, t in parts:
                ps.append(p); ns.append(n); ts.append(t + base)
                base += len(p)
            prim = {"attributes": {
                "POSITION": put(np.concatenate(ps).astype(np.float32), 34962, "VEC3", 5126),
                "NORMAL": put(np.concatenate(ns).astype(np.float32), 34962, "VEC3", 5126)},
                "indices": put(np.concatenate(ts).astype(np.uint32).ravel(), 34963, "SCALAR", 5125)}
            if material >= 0:
                prim["material"] = material
            prims.append(prim)
            stats.append((group, material, base, sum(len(t) for _, _, t in parts)))
        out["meshes"].append({"name": group, "primitives": prims})
        node = {"name": group, "mesh": len(out["meshes"]) - 1}
        if group in wheel_rest:
            node["translation"] = wheel_rest[group].tolist()
        out["nodes"].append(node)
        out["nodes"][0]["children"].append(len(out["nodes"]) - 1)
    out["buffers"].append({"byteLength": len(blob)})

    js = json.dumps(out, separators=(",", ":")).encode()
    js += b" " * (-len(js) % 4)
    while len(blob) % 4:
        blob.append(0)
    with open(dst, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(blob)))
        f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
        f.write(struct.pack("<II", len(blob), 0x004E4942) + bytes(blob))

    names = [m.get("name", "?") for m in gltf.get("materials", [])]
    for group, material, verts, tris in stats:
        print("%-8s %-36s %7d vertices %7d triangles" % (group, names[material] if material >= 0 else "-", verts, tris))
    missing = [w for w in ("wheel_0", "wheel_1", "wheel_2", "wheel_3") if w not in groups]
    if missing:
        print("note: no wheel nodes found for", ", ".join(missing), "(those wheels will not turn)")


if __name__ == "__main__":
    main()
