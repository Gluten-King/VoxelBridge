import json
import os
import struct
from pathlib import Path

# Blender-only imports
import bpy

ROTATE_X_90 = False


def _read_bytes(path):
    with open(path, "rb") as f:
        return f.read()


def _slice(buf, offset, length):
    return memoryview(buf)[offset:offset + length]


def _read_f32x3(buf, count):
    out = [None] * count
    for i in range(count):
        base = i * 12
        out[i] = struct.unpack_from("<fff", buf, base)
    return out


def _read_u32(buf, count):
    out = [0] * count
    for i in range(count):
        out[i] = struct.unpack_from("<I", buf, i * 4)[0]
    return out


def _read_u16(buf, count):
    out = [0] * count
    for i in range(count):
        out[i] = struct.unpack_from("<H", buf, i * 2)[0]
    return out


def _read_loop_attr(buf, loop_count):
    normals = [None] * loop_count
    colors = [None] * loop_count
    for i in range(loop_count):
        base = i * 16
        nx, ny, nz = struct.unpack_from("<fff", buf, base)
        r, g, b, a = struct.unpack_from("<BBBB", buf, base + 12)
        normals[i] = (nx, ny, nz)
        colors[i] = (r / 255.0, g / 255.0, b / 255.0, a / 255.0)
    return normals, colors


def _read_uv_loop(buf, loop_count, atlas_size, normalized=False):
    uvs = [None] * loop_count
    for i in range(loop_count):
        base = i * 8
        u = struct.unpack_from("<H", buf, base)[0]
        v = struct.unpack_from("<H", buf, base + 2)[0]
        if normalized:
            uvs[i] = (u / 65535.0, v / 65535.0)
        else:
            uvs[i] = (u / float(atlas_size), v / float(atlas_size))
    return uvs


def import_vxb(json_path):
    json_path = Path(json_path)
    data = json.loads(json_path.read_text(encoding="utf-8"))
    base_dir = json_path.parent

    buffers = {b["name"]: (base_dir / b["uri"]).resolve() for b in data["buffers"]}
    atlas_size = int(data.get("atlasSize", 8192))
    uv1_quant = data.get("uv1Quantization", "normalized_u16")

    bin_cache = {}
    for name, path in buffers.items():
        if not path.exists():
            raise FileNotFoundError(f"Missing buffer: {path}")
        bin_cache[name] = _read_bytes(path)

    collection = bpy.data.collections.get("VXB")
    if collection is None:
        collection = bpy.data.collections.new("VXB")
        bpy.context.scene.collection.children.link(collection)

    for mesh_idx, mesh_info in enumerate(data["meshes"]):
        base_name = mesh_info.get("name", f"mesh_{mesh_idx}")
        sections = mesh_info.get("sections")
        if not sections:
            sections = [{
                "vertexCount": mesh_info.get("vertexCount", 0),
                "faceCount": mesh_info.get("faceCount", 0),
                "faceIndexCount": mesh_info.get("faceIndexCount", 0),
                "indexCount": mesh_info.get("indexCount", 0),
                "views": mesh_info.get("views", {})
            }]

        for sec_idx, section in enumerate(sections):
            name = f"{base_name}_{sec_idx}" if len(sections) > 1 else base_name
            views = section["views"]

            pos_view = views["POSITION"]
            idx_view = views["INDEX"]
            uv_view = views["UV_LOOP"]
            uv1_view = views.get("UV1_LOOP")
            loop_view = views["LOOP_ATTR"]
            face_count_view = views.get("FACE_COUNT")
            face_index_view = views.get("FACE_INDEX")

            vcount = int(section["vertexCount"])
            face_count = int(section.get("faceCount", 0))
            face_index_count = int(section.get("faceIndexCount", 0))

            pos_buf = _slice(bin_cache[pos_view["buffer"]], pos_view["offset"], vcount * 12)
            vertices = _read_f32x3(pos_buf, vcount)
            if ROTATE_X_90:
                vertices = [(v[0], -v[2], v[1]) for v in vertices]

            if face_count_view and face_index_view and face_count > 0 and face_index_count > 0:
                fc_buf = _slice(bin_cache[face_count_view["buffer"]], face_count_view["offset"], face_count * 2)
                fi_buf = _slice(bin_cache[face_index_view["buffer"]], face_index_view["offset"], face_index_count * 4)
                face_counts = _read_u16(fc_buf, face_count)
                face_indices = _read_u32(fi_buf, face_index_count)

                faces = []
                cursor = 0
                for c in face_counts:
                    faces.append(face_indices[cursor:cursor + c])
                    cursor += c
            else:
                icount = int(section["indexCount"])
                idx_buf = _slice(bin_cache[idx_view["buffer"]], idx_view["offset"], icount * 4)
                indices = _read_u32(idx_buf, icount)
                faces = [indices[i:i + 3] for i in range(0, len(indices), 3)]

            mesh = bpy.data.meshes.new(name)
            mesh.from_pydata(vertices, [], faces)
            mesh.update()

            obj = bpy.data.objects.new(name, mesh)
            collection.objects.link(obj)

            # UV0
            loop_count = sum(len(f) for f in faces)
            uv_buf = _slice(bin_cache[uv_view["buffer"]], uv_view["offset"], loop_count * 8)
            uv0 = _read_uv_loop(uv_buf, loop_count, atlas_size, normalized=False)
            uv_layer = mesh.uv_layers.new(name="UV0")
            for i, uv in enumerate(uv0):
                uv_layer.data[i].uv = uv

            # UV1 (overlay / colormap)
            if uv1_view is not None:
                uv1_buf = _slice(bin_cache[uv1_view["buffer"]], uv1_view["offset"], loop_count * 8)
                if uv1_quant == "atlas_u16":
                    uv1 = _read_uv_loop(uv1_buf, loop_count, atlas_size, normalized=False)
                else:
                    uv1 = _read_uv_loop(uv1_buf, loop_count, atlas_size, normalized=True)
                uv1_layer = mesh.uv_layers.new(name="UV1")
                for i, uv in enumerate(uv1):
                    uv1_layer.data[i].uv = uv

            # Vertex color from LOOP_ATTR
            loop_buf = _slice(bin_cache[loop_view["buffer"]], loop_view["offset"], loop_count * 16)
            _, colors = _read_loop_attr(loop_buf, loop_count)
            color_layer = mesh.color_attributes.new(name="Color", type='BYTE_COLOR', domain='CORNER')
            for i, col in enumerate(colors):
                color_layer.data[i].color = (col[0], col[1], col[2], col[3])

    print(f"Imported VXB from {json_path}")


# Entry point for Blender Text Editor
# Replace with your exported vxb.json path
VXB_JSON_PATH = r"D:\\ModrinthApp\\profiles\\1.21.1\\export\\2025-12-18_08-33-03\\vxb\\region_7_20_30__7_20_30.vxb"

import_vxb(VXB_JSON_PATH)
