# Porting VoxelBridge to a new Minecraft version

1. Create `runtime/minecraft/<version>` with its one-line module build and
   implement the pipeline runtime ports against that exact Minecraft API. Add
   only proven source-compatible roots to its registry entry; do not copy
   pipeline exporters.
2. Create or update the Fabric/NeoForge target with lifecycle, model-extension,
   mixin, and Mod hooks only.
3. Declare `RuntimeCapabilities` honestly. Missing required capabilities must
   produce a structured diagnostic; never return fabricated UVs or textures.
4. Register the target, source roots, Java toolchain, and dependency lock once
   in `gradle/targets.json`, then update the Prism/Golden target manifest.
5. Run `testFast` and `verifyProductionJars`, adapter contract probes, focused production Golden scenes,
   and finally the complete release matrix.

Changes caused only by renamed classes, renderer submission APIs, vertex
layouts, or texture access belong in the version adapter. Change pipeline only
when Minecraft introduces a render fact that the stable contract cannot express;
such a change requires an additive contract field/capability, tests, and an
output schema review.

Cross-version reflection is not the primary compatibility mechanism. It is
permitted only as a narrowly scoped fallback for inaccessible runtime internals,
must be isolated in the exact-version adapter, and must emit a probe when used.
