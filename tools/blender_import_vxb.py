import json
import os
import re
import struct
import mmap
from pathlib import Path

# Blender-only imports
import bpy
from bpy.props import BoolProperty, StringProperty
from bpy.types import Operator

ROTATE_X_90 = True


def _read_bytes(path):
    with open(path, "rb") as f:
        return mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)


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
        base = i * 12
        u = struct.unpack_from("<f", buf, base)[0]
        v = struct.unpack_from("<f", buf, base + 4)[0]
        if normalized:
            uvs[i] = (u, v)
        else:
            uvs[i] = (u / float(atlas_size), v / float(atlas_size))
    return uvs


def _read_uv1_loop(buf, loop_count, atlas_size, uv1_quant, colormap_mode):
    uvs = [None] * loop_count
    for i in range(loop_count):
        base = i * 12
        u = struct.unpack_from("<f", buf, base)[0]
        v = struct.unpack_from("<f", buf, base + 4)[0]
        packed = struct.unpack_from("<H", buf, base + 8)[0]
        if uv1_quant == "atlas_f32":
            uu = u / float(atlas_size)
            vv = v / float(atlas_size)
        else:
            uu = u
            vv = v
        if colormap_mode:
            tile_u = packed % 10
            tile_v = packed // 10
            uu = uu + tile_u
            vv = vv - tile_v  
        uvs[i] = (uu, vv)
    return uvs


def import_vxb(json_path, validate_mesh=True):
    json_path = Path(json_path)
    try:
        text = json_path.read_text(encoding="utf-8")
    except UnicodeDecodeError as exc:
        raise RuntimeError(f"Failed to read {json_path} as UTF-8. Please ensure you selected the exported .vxb JSON file, not a binary buffer file.") from exc
    data = json.loads(text)
    base_dir = json_path.parent

    buffers = {b["name"]: (base_dir / b["uri"]).resolve() for b in data["buffers"]}
    atlas_size = int(data.get("atlasSize", 8192))
    uv1_quant = data.get("uv1Quantization", "normalized_f32")
    color_mode = data.get("colorMode", "VERTEX_COLOR")
    colormap_mode = color_mode.upper() == "COLORMAP"

    bin_cache = {}
    for name, path in buffers.items():
        if not path.exists():
            raise FileNotFoundError(f"Missing buffer: {path}")
        bin_cache[name] = _read_bytes(path)

    collection = bpy.data.collections.get("VXB")
    if collection is None:
        collection = bpy.data.collections.new("VXB")
        bpy.context.scene.collection.children.link(collection)

    # merge_key -> accumulators
    merged = {}

    total_transparent_faces = 0
    total_degenerate_faces = 0

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

            # Strip chunk/section separators (e.g., "__" in the name)
            base_merge = name.split("__", 1)[0]
            # Remove trailing numeric suffixes (e.g., dirt1, dirt_2 -> dirt)
            merge_key = re.sub(r"(?:_?\d+)+$", "", base_merge)
            bucket = merged.setdefault(merge_key, {
                "verts": [],
                "faces": [],
                "uv0": [],
                "uv1": [],
                "colors": [],
                "has_uv1": False,
            })

            base_idx = len(bucket["verts"])
            bucket["verts"].extend(vertices)

            loop_count = sum(len(f) for f in faces)
            uv_buf = _slice(bin_cache[uv_view["buffer"]], uv_view["offset"], loop_count * 12)
            uv0_raw = _read_uv_loop(uv_buf, loop_count, atlas_size, normalized=False)

            uv1_raw = None
            if uv1_view is not None:
                uv1_buf = _slice(bin_cache[uv1_view["buffer"]], uv1_view["offset"], loop_count * 12)
                uv1_raw = _read_uv1_loop(uv1_buf, loop_count, atlas_size, uv1_quant, colormap_mode)
                bucket["has_uv1"] = True

            loop_buf = _slice(bin_cache[loop_view["buffer"]], loop_view["offset"], loop_count * 16)
            _, colors_raw = _read_loop_attr(loop_buf, loop_count)

            # Iterate through faces, skip fully transparent and degenerate faces
            loop_cursor = 0
            for f in faces:
                f_len = len(f)
                face_colors = colors_raw[loop_cursor:loop_cursor + f_len]

                # Check for fully transparent faces
                is_transparent = all(c[3] == 0.0 for c in face_colors)

                # Check for degenerate faces (duplicate vertex indices)
                is_degenerate = len(set(f)) < f_len

                if is_transparent:
                    total_transparent_faces += 1
                elif is_degenerate:
                    total_degenerate_faces += 1
                else:
                    # Only accumulate valid face data
                    face_uv0 = uv0_raw[loop_cursor:loop_cursor + f_len]
                    if uv1_raw is not None:
                        face_uv1 = uv1_raw[loop_cursor:loop_cursor + f_len]
                    else:
                        face_uv1 = [(0.0, 0.0)] * f_len

                    bucket["faces"].append([idx + base_idx for idx in f])
                    bucket["uv0"].extend(face_uv0)
                    bucket["uv1"].extend(face_uv1)
                    bucket["colors"].extend(face_colors)

                loop_cursor += f_len

    for merge_key, bucket in merged.items():
        verts = bucket["verts"]
        faces = bucket["faces"]
        uv0 = bucket["uv0"]
        uv1 = bucket["uv1"]
        colors = bucket["colors"]
        has_uv1 = bucket["has_uv1"]

        mesh = bpy.data.meshes.new(merge_key)
        mesh.from_pydata(verts, [], faces)

        # Skip mesh.validate() - we already filtered degenerate and fully transparent faces during import
        # validate() modifies mesh topology (removes degenerate faces, merges vertices, etc.),
        # causing loop count mismatch with our prepared UV/color data

        loop_total = len(mesh.loops)
        if loop_total != len(uv0):
            raise RuntimeError(f"UV0 loop count mismatch for {merge_key}: loops={loop_total}, uv0={len(uv0)}")

        uv_layer0 = mesh.uv_layers.new(name="UV0")
        uv_layer0.data.foreach_set("uv", [c for uv in uv0 for c in uv])

        if has_uv1:
            uv_layer1 = mesh.uv_layers.new(name="UV1")
            uv_layer1.data.foreach_set("uv", [c for uv in uv1 for c in uv])

        color_layer = mesh.color_attributes.new(name="Color", type='BYTE_COLOR', domain='CORNER')
        color_layer.data.foreach_set("color", [c for col in colors for c in col])

        mesh.update()

        obj = bpy.data.objects.new(merge_key, mesh)
        collection.objects.link(obj)

    print(f"Imported VXB from {json_path}")
    if validate_mesh and (total_transparent_faces > 0 or total_degenerate_faces > 0):
        print(f"  Skipped {total_transparent_faces} transparent faces and {total_degenerate_faces} degenerate faces")


class VXB_OT_import(Operator):
    bl_idname = "wm.vxb_import"
    bl_label = "Import VXB"
    bl_options = {"REGISTER", "UNDO"}

    filepath: StringProperty(subtype="FILE_PATH")
    validate_mesh: BoolProperty(
        name="Validate Mesh",
        description="Call mesh.validate to clean degenerate geometry",
        default=True,
    )

    def execute(self, context):
        if not self.filepath:
            self.report({"ERROR"}, "No VXB file selected")
            return {"CANCELLED"}
        try:
            import_vxb(self.filepath, self.validate_mesh)
        except Exception as exc:  # pragma: no cover - Blender runtime
            self.report({"ERROR"}, f"Import failed: {exc}")
            raise
        return {"FINISHED"}

    def invoke(self, context, event):
        context.window_manager.fileselect_add(self)
        return {"RUNNING_MODAL"}


def register():
    bpy.utils.register_class(VXB_OT_import)


def unregister():
    bpy.utils.unregister_class(VXB_OT_import)


if __name__ == "__main__":  # pragma: no cover
    register()
    bpy.ops.wm.vxb_import("INVOKE_DEFAULT")
