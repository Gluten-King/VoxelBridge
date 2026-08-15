"""Blender add-on and automation helper for VoxelBridge material alpha links."""

bl_info = {
    "name": "VoxelBridge Material Alpha",
    "author": "VoxelBridge",
    "version": (1, 0, 0),
    "blender": (4, 2, 0),
    "location": "Shader Editor > Sidebar > VoxelBridge",
    "description": "Connect Base Color texture alpha to Principled BSDF alpha",
    "category": "Material",
}

import bpy


def _upstream_nodes(input_socket):
    pending = [link.from_node for link in input_socket.links]
    visited = set()
    while pending:
        node = pending.pop(0)
        pointer = node.as_pointer()
        if pointer in visited:
            continue
        visited.add(pointer)
        yield node
        for node_input in node.inputs:
            pending.extend(link.from_node for link in node_input.links)


def _find_principled(material):
    nodes = material.node_tree.nodes
    outputs = [node for node in nodes if node.type == "OUTPUT_MATERIAL"]
    outputs.sort(key=lambda node: not getattr(node, "is_active_output", False))
    for output in outputs:
        surface = output.inputs.get("Surface")
        if surface is None:
            continue
        for node in _upstream_nodes(surface):
            if node.type == "BSDF_PRINCIPLED":
                return node
    return next((node for node in nodes if node.type == "BSDF_PRINCIPLED"), None)


def _find_base_color_texture(material, principled):
    base_color = principled.inputs.get("Base Color")
    if base_color is not None:
        for node in _upstream_nodes(base_color):
            if node.type == "TEX_IMAGE" and node.outputs.get("Alpha") is not None:
                return node

    # This fallback keeps the operator useful for hand-edited glTF materials
    # whose Base Color link was temporarily disconnected.
    candidates = []
    for node in material.node_tree.nodes:
        if node.type != "TEX_IMAGE" or node.outputs.get("Alpha") is None:
            continue
        normalized_name = f"{node.name} {node.label}".lower().replace("_", " ")
        if "base color" in normalized_name or "basecolor" in normalized_name:
            return node
        candidates.append(node)
    return candidates[0] if len(candidates) == 1 else None


def connect_base_color_alpha(materials=None):
    """Connect each material's Base Color image alpha directly to its BSDF.

    Existing links on the Principled Alpha input are replaced only when a
    matching Base Color image can be identified. Material blend/render modes
    and all other node links remain untouched.
    """

    materials = bpy.data.materials if materials is None else materials
    stats = {
        "examined": 0,
        "changed": 0,
        "already_connected": 0,
        "skipped_no_nodes": 0,
        "skipped_no_principled": 0,
        "skipped_no_base_color_texture": 0,
    }
    for material in materials:
        stats["examined"] += 1
        if not material.use_nodes or material.node_tree is None:
            stats["skipped_no_nodes"] += 1
            continue
        principled = _find_principled(material)
        if principled is None or principled.inputs.get("Alpha") is None:
            stats["skipped_no_principled"] += 1
            continue
        texture = _find_base_color_texture(material, principled)
        if texture is None:
            stats["skipped_no_base_color_texture"] += 1
            continue

        alpha_output = texture.outputs.get("Alpha")
        alpha_input = principled.inputs.get("Alpha")
        existing = list(alpha_input.links)
        if len(existing) == 1 and existing[0].from_socket == alpha_output:
            stats["already_connected"] += 1
            continue
        for link in existing:
            material.node_tree.links.remove(link)
        material.node_tree.links.new(alpha_output, alpha_input)
        stats["changed"] += 1
    return stats


class VOXELBRIDGE_OT_connect_base_color_alpha(bpy.types.Operator):
    bl_idname = "voxelbridge.connect_base_color_alpha"
    bl_label = "Connect Base Color Alpha"
    bl_description = "Connect Base Color image alpha to Principled BSDF alpha for all materials"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, _context):
        stats = connect_base_color_alpha()
        self.report(
            {"INFO"},
            f"Updated {stats['changed']} materials; "
            f"{stats['already_connected']} were already connected",
        )
        return {"FINISHED"}


class VOXELBRIDGE_PT_material_alpha(bpy.types.Panel):
    bl_label = "Material Alpha"
    bl_idname = "VOXELBRIDGE_PT_material_alpha"
    bl_space_type = "NODE_EDITOR"
    bl_region_type = "UI"
    bl_category = "VoxelBridge"

    @classmethod
    def poll(cls, context):
        return context.space_data is not None and context.space_data.tree_type == "ShaderNodeTree"

    def draw(self, _context):
        self.layout.operator(VOXELBRIDGE_OT_connect_base_color_alpha.bl_idname)


CLASSES = (
    VOXELBRIDGE_OT_connect_base_color_alpha,
    VOXELBRIDGE_PT_material_alpha,
)


def register():
    for cls in CLASSES:
        bpy.utils.register_class(cls)


def unregister():
    for cls in reversed(CLASSES):
        bpy.utils.unregister_class(cls)


if __name__ == "__main__":
    register()
