# VXB Format (VoxelBridge eXchange Binary)

This document describes the current VXB export format produced by VoxelBridge.

## Overview

VXB uses a JSON header (`*.vxb`) plus two binary buffers (`*.bin` and
`*.uv.bin`). The header lists buffer views and scene info. Binary files store
raw geometry, UV loops, face topology, and loop attributes.

## Files

For an export named `region_X_Y_Z__X_Y_Z` under `.../export/<timestamp>/vxb/`:

- `region_*.vxb` (header, JSON)
- `region_*.bin` (geometry/indices/face data/loop attributes)
- `region_*.uv.bin` (UV0 + UV1 loop data)

All binary buffers are little-endian.

## JSON Header

Example (trimmed):

```json
{
  "version": 2,
  "endian": "LE",
  "atlasSize": 8192,
  "colorMode": "VERTEX_COLOR",
  "uv1Quantization": "atlas_f32",
  "colormapTextures": ["textures/colormap/colormap_1001.png"],
  "buffers": [
    {"name": "bin", "uri": "region_*.bin", "byteLength": 123},
    {"name": "uv", "uri": "region_*.uv.bin", "byteLength": 456}
  ],
  "sprites": [
    {
      "id": 0,
      "key": "minecraft:block/stone",
      "width": 16,
      "height": 16,
      "texture": "textures/atlas/atlas_1001.png",
      "atlas": {"page": 0, "x": 0, "y": 0, "w": 16, "h": 16}
    }
  ],
  "meshes": [
    {
      "name": "minecraft:stone",
      "vertexCount": 24,
      "indexCount": 36,
      "faceCount": 6,
      "faceIndexCount": 24,
      "doubleSided": false,
      "sections": [
        {
          "vertexCount": 24,
          "indexCount": 36,
          "faceCount": 6,
          "faceIndexCount": 24,
          "doubleSided": false,
          "views": {
            "POSITION": {"buffer": "bin", "offset": 0, "stride": 12, "type": "f32x3"},
            "INDEX": {"buffer": "bin", "offset": 0, "stride": 4, "type": "u32"},
            "UV_LOOP": {"buffer": "uv", "offset": 0, "stride": 12, "type": "f32x2_u16x2"},
            "UV1_LOOP": {"buffer": "uv", "offset": 0, "stride": 12, "type": "f32x2_u16x2"},
            "LOOP_ATTR": {"buffer": "bin", "offset": 0, "stride": 16, "type": "f32x3_u8x4"},
            "FACE_COUNT": {"buffer": "bin", "offset": 0, "stride": 2, "type": "u16"},
            "FACE_INDEX": {"buffer": "bin", "offset": 0, "stride": 4, "type": "u32"}
          }
        }
      ]
    }
  ],
  "nodes": [
    {"id": 0, "name": "minecraft:stone", "mesh": 0}
  ],
  "scenes": [
    {"nodes": [0]}
  ]
}
```

### Top-level fields

- `version`: format version, integer.
- `endian`: `"LE"` for little-endian.
- `atlasSize`: atlas tile size in pixels.
- `colorMode`: `VERTEX_COLOR` or `COLORMAP`.
- `uv1Quantization`: `"atlas_f32"` or `"normalized_f32"` (depending on color mode).
- `colormapTextures`: list of colormap textures used when `colorMode = COLORMAP`.
- `buffers`: list of named binary buffers.
- `sprites`: per-sprite atlas metadata.
- `meshes`: per-material mesh data and views.
- `nodes`, `scenes`: basic scene listing (flat nodes for now).

## Buffers

### POSITION (`bin`)

- Type: `float32 x, y, z`
- Stride: 12 bytes
- Count: `vertexCount`

### INDEX (`bin`)

- Type: `uint32`
- Stride: 4 bytes
- Count: `indexCount`
- Always triangles; used for render-only consumption.

### UV_LOOP (`uv.bin`)

- Type: `float32 u, float32 v, uint16 spriteId, uint16 pad`
- Stride: 12 bytes
- Count: `faceIndexCount` (one entry per face corner)
- `u,v` are atlas pixel coordinates (float, not quantized).

### UV1_LOOP (`uv.bin`)

- Type: `float32 u, float32 v, uint16 overlaySpriteId, uint16 pad`
- Stride: 12 bytes
- Count: `faceIndexCount`
- If `colorMode = COLORMAP`, `u,v` are normalized [0,1] colormap UVs.
- Otherwise, `u,v` are atlas pixel coordinates (float).

### LOOP_ATTR (`bin`)

- Type: `float32 nx, ny, nz` + `uint8 r, g, b, a`
- Stride: 16 bytes
- Count: `faceIndexCount`

### FACE_COUNT (`bin`)

- Type: `uint16`
- Stride: 2 bytes
- Count: `faceCount`
- Each entry is the vertex count for a face (e.g. 4 for quad).

### FACE_INDEX (`bin`)

- Type: `uint32`
- Stride: 4 bytes
- Count: `faceIndexCount`
- Flat list of vertex indices for faces, ordered by `FACE_COUNT`.

## UV Remapping Rules

UVs are already remapped to atlas coordinates during export.
For UV0, UVs are stored as atlas pixel coordinates (float).
For UV1, values are normalized [0,1] when `COLORMAP` is active; otherwise they
are atlas pixel coordinates (float).

## Notes

- `meshes` are grouped by `materialKey` (same key = one mesh).
- Each mesh may contain multiple `sections` (streamed chunks or batches).
- `FACE_*` is the authoritative topology for quads/ngons; `INDEX` is
  a triangulated fallback.
- Current nodes are flat; node hierarchy is reserved for future expansion.
