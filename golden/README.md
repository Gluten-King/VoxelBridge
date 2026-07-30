# VoxelBridge Golden Tests

This directory contains platform-neutral scene definitions and committed semantic
snapshots. A Minecraft world is only a disposable launcher environment; it is not
the golden result.

## Layout

- `scenarios/`: deterministic command lists and scenario manifests.
- `overrides/<minecraft-version>/`: commands needed only by one Minecraft version.
- `worlds/`: documentation and, later, pristine versioned world templates.
- `expected/<minecraft-version>/`: reviewed semantic snapshots shared by Fabric and NeoForge.

The verifier reads `.gltf`, every referenced binary buffer, and every referenced
image. It canonicalizes triangle arrival order, preserves winding, quantizes float
attributes to `1e-5`, hashes decoded RGBA pixels, evaluates the named semantic
assertions from `scenario.json`, and emits reviewable JSON.

Two semantic assertion types are supported:

- `material` matches exported material names and checks material, primitive,
  vertex, or triangle counts. This is used for entity and block-entity coverage.
- `face` counts matching triangles on a world-coordinate plane and optional
  bounds. This is used to prove that contact faces are culled while nearby
  control faces remain present.

Assertions use `expectedMaterials`/`minMaterials`/`maxMaterials` and equivalent
`Primitives`, `Vertices`, and `Triangles` fields. A failing assertion reports its
stable `id`, expected constraint, and observed count before golden comparison.
Material assertions may also use the equivalent `ColorVertices`,
`NonBlackColorVertices`, `NonWhiteColorVertices`, `UvVertices`,
`OutOfRangeUvVertices`, and `FullRangeUvPrimitives` fields. For example,
`minNonBlackColorVertices: 1` catches a renderer tint captured as black, while
`maxFullRangeUvPrimitives: 0` catches raw 0..1 UVs that were not remapped into
atlas placement space.

Generate a snapshot from an existing export:

```powershell
.\gradlew.bat generateGolden `
  "-Pgltf=path/to/scene.gltf" `
  "-Psnapshot=golden/expected/1.21.8/vanilla_smoke.snapshot.json" `
  "-Pscenario=vanilla_smoke" `
  "-PminecraftVersion=1.21.8" `
  "-PscenarioFile=golden/scenarios/vanilla_smoke/scene.mcfunction" `
  "-PscenarioManifest=golden/scenarios/vanilla_smoke/scenario.json"
```

Verify without modifying the expected snapshot:

```powershell
.\gradlew.bat verifyGolden `
  "-Pgltf=path/to/scene.gltf" `
  "-Pexpected=golden/expected/1.21.8/vanilla_smoke.snapshot.json" `
  "-Pscenario=vanilla_smoke" `
  "-PminecraftVersion=1.21.8" `
  "-PscenarioFile=golden/scenarios/vanilla_smoke/scene.mcfunction" `
  "-PscenarioManifest=golden/scenarios/vanilla_smoke/scenario.json"
```

## Fully automated client run

Create a tiny superflat world named `VoxelBridgeGolden` with commands enabled,
open it once in any loader for the target Minecraft version, then package it:

```powershell
.\gradlew.bat captureGoldenWorld "-PgoldenTarget=fabric-1.21.8"
```

The resulting `golden/worlds/1.21.8.zip` is shared by Fabric and NeoForge. To
start the client, copy the pristine world, execute the scene commands, export,
canonicalize, and stop automatically:

```powershell
.\gradlew.bat snapshotGoldenClient "-PgoldenTarget=fabric-1.21.8"
```

Review `build/golden-runs/fabric-1.21.8/vanilla_smoke/actual.snapshot.json` and
copy it into `golden/expected/1.21.8/` when establishing or intentionally
updating a baseline. Subsequent runs use:

```powershell
.\gradlew.bat verifyGoldenClient "-PgoldenTarget=fabric-1.21.8"
.\gradlew.bat verifyGoldenClient "-PgoldenTarget=neoforge-1.21.8"
```

Both loaders compare against the same expected snapshot.

Run the focused entity, block-entity, and nonsolid-culling scene with:

```powershell
.\gradlew.bat verifyGoldenClient `
  "-PgoldenTarget=fabric-1.21.11" `
  "-PgoldenScenario=render_features"
```

The focused scene covers entity and block-entity capture, same-leaves face
deduplication on all three axes, and leaves, stairs, slabs, and glass touching
solid blocks. The culling checks include EAST, SOUTH, and UP contacts. Each
culled contact has exterior and solid-side control-face assertions so an
over-culling regression cannot pass by merely deleting both sides.

Client automation reads `threadCount`, `atlasMode`, `coordinateMode`,
`exportDoubleSided`, and `nonsolidCulling` from the scenario manifest. Golden
client runs for the same target are protected by a cross-process lock so
overlapping invocations cannot replace each other's disposable world or output.

If NeoForge cannot quick-play a Fabric-created template, create a separate
NeoForge world and package it without changing the semantic baseline:

```powershell
.\gradlew.bat captureGoldenWorld `
  "-PgoldenTarget=neoforge-1.21.8" `
  "-PgoldenWorld=VoxelBridgeGoldenNeoForge" `
  "-PgoldenWorldArchive=neoforge-1.21.8.zip"
```

`prepareGoldenWorld` automatically prefers this platform-specific archive over
the shared `1.21.8.zip`.

Never update an expected snapshot automatically after a mismatch. Generate it to
a temporary path, review the semantic diff, and then replace the expected file.
