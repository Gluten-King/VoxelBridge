"""Deterministic Blender renderer for VoxelBridge golden review bundles.

Invoked by Gradle or the Prism runner, not directly by Minecraft. It imports
every glTF in the supplied manifest and emits one current/reference/diff set
per declared camera. Prism passes a per-instance manifest and blend filename;
other callers retain the review.blend default. The script intentionally uses
only Blender's bundled Python modules and never downloads dependencies.
"""

import argparse
import json
import math
import shutil
import sys
from pathlib import Path

import bpy
from mathutils import Vector

SCRIPT_DIRECTORY = Path(__file__).resolve().parent
if str(SCRIPT_DIRECTORY) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIRECTORY))
from voxelbridge_material_alpha import connect_base_color_alpha


def arguments():
    values = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--blend-name", default="review.blend")
    return parser.parse_args(values)


def reset_scene():
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete(use_global=False)
    for datablocks in (bpy.data.collections, bpy.data.cameras, bpy.data.lights):
        for datablock in list(datablocks):
            if datablock.users == 0:
                datablocks.remove(datablock)


def configure_render(scene):
    try:
        scene.render.engine = "BLENDER_EEVEE_NEXT"
    except TypeError:
        scene.render.engine = "BLENDER_EEVEE"
    scene.render.resolution_x = 768
    scene.render.resolution_y = 768
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.film_transparent = False
    scene.render.use_file_extension = True
    scene.view_settings.view_transform = "Standard"
    scene.view_settings.look = "Medium High Contrast"
    scene.view_settings.exposure = 0.0
    scene.view_settings.gamma = 1.0
    scene.world.color = (0.055, 0.055, 0.055)


def create_light(scene):
    data = bpy.data.lights.new("Golden_Key", "AREA")
    data.energy = 1400
    data.shape = "DISK"
    data.size = 12
    light = bpy.data.objects.new("Golden_Key", data)
    scene.collection.objects.link(light)
    light.location = (5.5, -7.0, 10.0)
    light.rotation_euler = (math.radians(28), 0, math.radians(35))

    fill_data = bpy.data.lights.new("Golden_Fill", "AREA")
    fill_data.energy = 650
    fill_data.size = 10
    fill = bpy.data.objects.new("Golden_Fill", fill_data)
    scene.collection.objects.link(fill)
    fill.location = (-6.0, 3.0, 6.0)
    return light, fill


def position_lights(lights, minimum, maximum):
    center = (minimum + maximum) * 0.5
    key, fill = lights
    key.location = center + Vector((5.5, -7.0, 10.0))
    fill.location = center + Vector((-6.0, 3.0, 6.0))
    key.rotation_euler = (center - key.location).to_track_quat("-Z", "Y").to_euler()
    fill.rotation_euler = (center - fill.location).to_track_quat("-Z", "Y").to_euler()


def import_item(item, offset):
    gltf = Path(item["gltf"])
    if not gltf.is_file():
        raise RuntimeError(f"Missing glTF for {item['caseId']}: {gltf}")
    before = set(bpy.data.objects)
    result = bpy.ops.import_scene.gltf(filepath=str(gltf))
    if "FINISHED" not in result:
        raise RuntimeError(f"Blender glTF import failed for {gltf}: {result}")
    imported = [obj for obj in bpy.data.objects if obj not in before]
    meshes = [obj for obj in imported if obj.type == "MESH"]
    if not meshes:
        raise RuntimeError(f"glTF imported without meshes: {gltf}")

    root = bpy.data.objects.new(f"CASE {item['caseId']}", None)
    bpy.context.scene.collection.objects.link(root)
    root.location.x = offset
    root["voxelbridge_case_id"] = item["caseId"]
    root["voxelbridge_source_gltf"] = str(gltf)
    imported_set = set(imported)
    for obj in imported:
        if obj.parent not in imported_set:
            obj.parent = root
    return root, imported


def world_bounds(objects):
    object_bounds = []
    for obj in objects:
        if obj.type != "MESH":
            continue
        points = [obj.matrix_world @ Vector(corner) for corner in obj.bound_box]
        minimum = Vector((min(p.x for p in points), min(p.y for p in points), min(p.z for p in points)))
        maximum = Vector((max(p.x for p in points), max(p.y for p in points), max(p.z for p in points)))
        object_bounds.append((minimum, maximum))

    # Beacon beams and similar renderer effects can extend hundreds of blocks
    # beyond a small selected region. Keep them visible, but do not let those
    # outliers make the actual test gallery a few pixels tall in review images.
    framing_bounds = [
        bounds for bounds in object_bounds
        if max(bounds[1] - bounds[0]) <= 64.0
    ] or object_bounds
    points = [point for bounds in framing_bounds for point in bounds]
    minimum = Vector((min(p.x for p in points), min(p.y for p in points), min(p.z for p in points)))
    maximum = Vector((max(p.x for p in points), max(p.y for p in points), max(p.z for p in points)))
    return minimum, maximum


def make_camera(scene, case_id, camera_config, minimum, maximum):
    camera_id = camera_config.get("id", "overview")
    center = (minimum + maximum) * 0.5
    if "target" in camera_config:
        configured = Vector(camera_config["target"])
        # Exported centered coordinates may differ from world coordinates; use
        # an explicit target only when it lies near the imported bounds.
        if all(minimum[i] - 1 <= configured[i] <= maximum[i] + 1 for i in range(3)):
            center = configured
    extent = maximum - minimum
    radius = max(extent.length * 0.5, 1.0)
    azimuth = math.radians(float(camera_config.get("azimuth", 45.0)))
    elevation = math.radians(float(camera_config.get("elevation", 30.0)))
    margin = float(camera_config.get("margin", 1.2))
    direction = Vector((
        math.cos(elevation) * math.sin(azimuth),
        -math.cos(elevation) * math.cos(azimuth),
        math.sin(elevation),
    ))
    distance = radius * 2.8 * max(margin, 1.0)

    camera_data = bpy.data.cameras.new(f"{case_id}__{camera_id}")
    camera_data.lens = 50
    camera_data.clip_start = 0.01
    camera_data.clip_end = max(1000.0, distance * 10)
    camera = bpy.data.objects.new(camera_data.name, camera_data)
    scene.collection.objects.link(camera)
    camera.location = center + direction * distance
    camera.rotation_euler = (center - camera.location).to_track_quat("-Z", "Y").to_euler()
    camera["voxelbridge_case_id"] = case_id
    camera["voxelbridge_camera_id"] = camera_id
    return camera


def render_camera(scene, camera, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    scene.camera = camera
    scene.render.filepath = str(destination)
    bpy.ops.render.render(write_still=True)


def write_diff(current_path, reference_path, diff_path, heatmap_path):
    if not reference_path.is_file():
        return
    current = bpy.data.images.load(str(current_path), check_existing=False)
    reference = bpy.data.images.load(str(reference_path), check_existing=False)
    try:
        if tuple(current.size) != tuple(reference.size):
            return
        current_pixels = list(current.pixels)
        reference_pixels = list(reference.pixels)
        differences = [0.0] * len(current_pixels)
        heat = [0.0] * len(current_pixels)
        for index in range(0, len(current_pixels), 4):
            rgb = [abs(current_pixels[index + channel] - reference_pixels[index + channel]) for channel in range(3)]
            maximum = max(rgb)
            differences[index : index + 4] = [rgb[0], rgb[1], rgb[2], 1.0]
            heat[index : index + 4] = [maximum, min(1.0, maximum * 0.5), 0.0, 1.0]
        save_pixels(current.size, differences, diff_path, "diff")
        save_pixels(current.size, heat, heatmap_path, "heatmap")
    finally:
        bpy.data.images.remove(current)
        bpy.data.images.remove(reference)


def save_pixels(size, pixels, path, label):
    path.parent.mkdir(parents=True, exist_ok=True)
    image = bpy.data.images.new(label, width=size[0], height=size[1], alpha=True)
    try:
        image.pixels = pixels
        image.file_format = "PNG"
        image.filepath_raw = str(path)
        image.save()
    finally:
        bpy.data.images.remove(image)


def main():
    args = arguments()
    manifest_path = Path(args.manifest).resolve()
    output = Path(args.output).resolve()
    items = json.loads(manifest_path.read_text(encoding="utf-8"))
    # Accept legacy one-case manifests that PowerShell serialized as a JSON
    # object. New manifests are always arrays, but this keeps existing run
    # artifacts reviewable and makes the consumer tolerant at the boundary.
    if isinstance(items, dict):
        items = [items]
    if not isinstance(items, list) or not all(isinstance(item, dict) for item in items):
        raise ValueError("Blender review manifest must be an object or an array of objects")
    output.mkdir(parents=True, exist_ok=True)

    reset_scene()
    scene = bpy.context.scene
    configure_render(scene)
    lights = create_light(scene)
    imported_cases = []
    offset = 0.0
    for item in items:
        root, imported = import_item(item, offset)
        minimum, maximum = world_bounds(imported)
        case_width = max(8.0, maximum.x - minimum.x + 6.0)
        imported_cases.append((item, root, imported, minimum, maximum))
        offset += case_width

    alpha_stats = connect_base_color_alpha()
    print(f"VoxelBridge Base Color alpha links: {json.dumps(alpha_stats, sort_keys=True)}")
    for key, value in alpha_stats.items():
        scene[f"voxelbridge_alpha_{key}"] = value

    for item, root, imported, minimum, maximum in imported_cases:
        for other_item, other_root, _, _, _ in imported_cases:
            other_root.hide_render = other_root != root
        position_lights(lights, minimum, maximum)
        render_root = Path(item["outputDirectory"])
        reference_root = Path(item["referenceDirectory"])
        for camera_config in item.get("cameras") or [{"id": "overview"}]:
            camera = make_camera(scene, item["caseId"], camera_config, minimum, maximum)
            camera_id = camera_config.get("id", "overview")
            current = render_root / "current" / f"{camera_id}.png"
            reference = reference_root / f"{camera_id}.png"
            render_camera(scene, camera, current)
            write_diff(
                current,
                reference,
                render_root / "diff" / f"{camera_id}.png",
                render_root / "heatmap" / f"{camera_id}.png",
            )
            if reference.is_file():
                destination = render_root / "reference" / f"{camera_id}.png"
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(reference, destination)

    for _, root, _, _, _ in imported_cases:
        root.hide_render = False
    scene["voxelbridge_review_manifest"] = str(manifest_path)
    scene["voxelbridge_review_case_count"] = len(imported_cases)
    blend_name = Path(args.blend_name).name
    if not blend_name.lower().endswith(".blend"):
        raise ValueError("--blend-name must end with .blend")
    bpy.ops.wm.save_as_mainfile(filepath=str(output / blend_name))


if __name__ == "__main__":
    main()
