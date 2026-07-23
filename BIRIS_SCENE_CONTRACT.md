# VoxelBridge Minecraft Scene Contract v2

This contract augments glTF 2.0 without changing the meaning of standard glTF
attributes. Consumers that do not understand the extension can still display
the base scene.

## Extension declarations

Assets declare:

```json
"extensionsUsed": [
  "VOXELBRIDGE_minecraft_scene",
  "VOXELBRIDGE_minecraft_material"
]
```

The root `VOXELBRIDGE_minecraft_scene` object contains:

- `version`: contract version, currently `2`.
- `minecraftVersion`: producer Minecraft version.
- `colorUvTexCoord`: `1`.
- `lightUvTexCoord`: `2`.
- `lightUvEncoding`: `normalized-minecraft-0-240`.
- `midTexCoordTexCoord`: `3`.
- `midTexCoordSemantic`: `mc_midTexCoord`.
- `midBlockAttribute`: `_VOXELBRIDGE_MID_BLOCK`.
- `midBlockEncoding`: `iris-offset-to-block-center-times-64-emission-w`.
- `materialIdentityTexCoord`: `4`.
- `materialIdentityEncoding`: `index-in-u-into-materialIdentities`.
- `materialIdentities`: stable, sorted identity dictionary used by primitives
  and `TEXCOORD_4.x`.
- `propertyDomains`: declares which dictionary fields are inputs for
  `block.properties`, `entity.properties`, and `item.properties` matching.
- `lightmapTexture`: optional glTF texture index for the captured Minecraft
  light texture.
- `lightmapEncoding`: `minecraft-light-texture-16x16`.
- `lightmapColorSpace`: `linear`.

When present, `lightmapTexture` references `lightmap.png`, captured from the
client's current `LightTexture` on the render thread. Its sampler uses linear
filtering and clamp-to-edge. The image is scene-level state and is not copied
into every material.

## Vertex attributes

| Attribute | Type | Meaning |
| --- | --- | --- |
| `POSITION` | float `VEC3` | glTF Y-up position |
| `NORMAL` | float `VEC3` | normalized geometric/render normal |
| `TANGENT` | float `VEC4` | tangent XYZ and bitangent handedness W |
| `TEXCOORD_0` | float `VEC2` | base texture or atlas coordinates |
| `TEXCOORD_1` | float `VEC2` | existing VoxelBridge colormap/overlay coordinates |
| `TEXCOORD_2` | float `VEC2` | normalized block-light and sky-light coordinates |
| `TEXCOORD_3` | float `VEC2` | final-UV center of the current quad (`mc_midTexCoord`) |
| `TEXCOORD_4` | float `VEC2` | material identity in X; Y is reserved as zero |
| `COLOR_0` | float `VEC4` | linear vertex tint and alpha |
| `_VOXELBRIDGE_MID_BLOCK` | float `VEC4` | Iris `at_midBlock`; authoritative for terrain |

`TEXCOORD_2` components are finite values in `[0, 1]`. Multiplying by `240`
recovers the Minecraft light coordinate. Values captured from a Minecraft
`VertexConsumer` retain its `setUv2` coordinates before normalization.
Static block-model faces currently sample the neighboring light-owning block
and encode each light level as `level * 16`.

`TEXCOORD_1` must not be treated as a lightmap coordinate.

`TEXCOORD_3` is calculated after atlas remapping. All four
vertices of a quad receive the arithmetic mean of that quad's final
`TEXCOORD_0` values.

`TEXCOORD_4.x` is the exact integer index into `materialIdentities`.
`TEXCOORD_4.y` is zero in v2. The semantic streams use consecutive standard
glTF `TEXCOORD_n` accessors so ordinary importers preserve them deterministically.
`TEXCOORD_1` is emitted even when every value is zero, preventing consumers
that stop at the first missing UV-set index from overlooking later sets.

For terrain, `_VOXELBRIDGE_MID_BLOCK.xyz` is `(blockCenter - POSITION) * 64`.
Adding `xyz / 64` to `POSITION` therefore recovers the same block center for
all four vertices. W stores the block's own emission level in `[0, 15]`.
Non-terrain primitives currently store zero because Iris does not expose
`at_midBlock` to entity/block-entity programs.

## Material identity dictionary

Each entry in root `materialIdentities` contains an integer `id` plus the
available stable fields:

- `objectClass`: `terrain`, `entity`, or `block_entity`.
- `materialKey`: primary Minecraft registry identity, independent of visual
  atlas/per-sprite material naming.
- `blockId`: namespaced block registry ID.
- `blockState`: canonical full state, for example
  `minecraft:oak_stairs[facing=north,half=bottom,...]`.
- `entityType`: namespaced entity type.
- `blockEntityId`: namespaced block entity type.
- `itemId`: namespaced item ID when the captured source exposes an item
  (including item entities, item displays, and item frames).

VoxelBridge splits its internal visual buckets by this identity before writing
glTF primitives. Consequently a primitive and all of its vertices use one
dictionary ID even in packed-atlas mode. This is the static-scene equivalent
of per-quad identity and does not require one image per material.

`block.properties` consumers match `blockId` plus selectors from `blockState`.
`entity.properties` consumers match `entityType`; `item.properties` consumers
match `itemId`. The numeric IDs assigned by a shader pack are consumer output
and are deliberately not baked into the VoxelBridge file.

## Material extension

Each material declares `VOXELBRIDGE_minecraft_material`:

- `renderLayer`: `unknown`, `solid`, `cutout`, or `translucent`.
- `emissive`: whether any geometry in the material is emissive.
- `normalTexture`: optional glTF texture index for the aligned LabPBR normal
  texture.
- `specularTexture`: optional glTF texture index for the aligned LabPBR
  specular texture.
- `materialIdentity`: optional dictionary index when material identity export
  is set to `REGISTRY`.
- identity fields copied from that dictionary entry for simple consumers.
- `identityEncoding`: `voxelbridge-scene-material-identity`.

The material also uses standard glTF presentation hints:

- solid: `alphaMode=OPAQUE`
- cutout: `alphaMode=MASK`
- translucent: `alphaMode=BLEND`
- emissive: `emissiveFactor=[1,1,1]`

The custom fields remain authoritative for Minecraft/Iris rendering because
LabPBR textures and Minecraft render layers are not equivalent to standard
metallic-roughness material semantics.

## Consumer requirements

A strict v2 consumer must reject a primitive that declares
`VOXELBRIDGE_minecraft_scene` but lacks `NORMAL`, a VEC4 `TANGENT`, a VEC2
`TEXCOORD_1`, a VEC2 `TEXCOORD_2`, a VEC2 `TEXCOORD_3`, or a VEC4
`_VOXELBRIDGE_MID_BLOCK` accessor with the same count as `POSITION`.

A primitive with `materialIdentity` must also have a matching VEC2
`TEXCOORD_4`: X equals the declared identity and Y is zero.

Consumers may continue to accept v1 files, where light UV, mid-UV, and
material identity used `_VOXELBRIDGE_LIGHT_UV`,
`_VOXELBRIDGE_MID_TEX_COORD`, and `_VOXELBRIDGE_MATERIAL_ID`.

Unknown extension fields must be ignored for forward compatibility.

## Planned compatible additions

Future versions may add biome identity and scene environment values. They
will use new fields and will not repurpose the v2 `TEXCOORD_1..4` layout.
