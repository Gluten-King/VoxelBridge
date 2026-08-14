# Mod renderer integration

Loader integrations belong in `fabric/<version>` or `neoforge/<version>`.
They may call loader model extensions, obtain model data, and capture a Mod's
final renderer submissions, but must normalize results before crossing into
pipeline.

For an ordinary block-model integration:

1. Invoke the loader's real baked-model extension for the exact game version.
2. Emit `QuadInput` with final positions, UVs, sprite bounds, cull face,
   material facts, and provenance.
3. Advertise `MOD_MODEL_GEOMETRY` only when that path is actually active.

For entities, block entities, glyphs, or custom renderers:

1. Capture the final submitted vertices and actual bound texture/render state.
2. Emit `CapturedPrimitive`; leave absent UVs absent instead of inventing them.
3. Use a conservative fallback and a structured diagnostic when final state
   cannot be observed.

Every new Mod integration requires a locked Mod version, a minimal Golden
scene, relevant probe assertions, and an explicit target version. Main must
not emit BIRIS/linker-only attributes.
