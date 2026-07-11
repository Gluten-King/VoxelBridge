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
attributes to `1e-5`, hashes decoded RGBA pixels, and emits reviewable JSON.

Generate a snapshot from an existing export:

```powershell
.\gradlew.bat generateGolden `
  "-Pgltf=path/to/scene.gltf" `
  "-Psnapshot=golden/expected/1.21.8/vanilla_smoke.snapshot.json" `
  "-Pscenario=vanilla_smoke" `
  "-PminecraftVersion=1.21.8" `
  "-PscenarioFile=golden/scenarios/vanilla_smoke/scene.mcfunction"
```

Verify without modifying the expected snapshot:

```powershell
.\gradlew.bat verifyGolden `
  "-Pgltf=path/to/scene.gltf" `
  "-Pexpected=golden/expected/1.21.8/vanilla_smoke.snapshot.json" `
  "-Pscenario=vanilla_smoke" `
  "-PminecraftVersion=1.21.8" `
  "-PscenarioFile=golden/scenarios/vanilla_smoke/scene.mcfunction"
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
