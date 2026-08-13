# Prism production-like instance matrix

This directory defines the local Prism Launcher instances used to exercise the
real VoxelBridge release JARs outside Gradle development runs.

The default launcher root is:

`D:\PrismLauncher-Windows-MinGW-w64-Portable-11.0.3`

Generate or refresh the managed instances while Prism Launcher is closed:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\prism\Configure-PrismMatrix.ps1
```

The launcher root, Modrinth profile root, and Java 17/21 executables can all be
overridden with script parameters. Machine-specific paths are intentionally not
stored in `instances.json`.

Launch an instance by its stable folder ID:

```powershell
& 'D:\PrismLauncher-Windows-MinGW-w64-Portable-11.0.3\prismlauncher.exe' --launch vb-fabric-1.21.11-base
```

Open an instance's Prism page without launching Minecraft:

```powershell
& 'D:\PrismLauncher-Windows-MinGW-w64-Portable-11.0.3\prismlauncher.exe' --show vb-fabric-1.21.11-base
```

Prepare and open the interactive 1.21.11 showcase world:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\prism\Open-PrismShowcase.ps1
```

The showcase uses Prism's singleplayer quick-play support, keeps Minecraft open
after VoxelBridge finishes the atlas export, and never modifies the source world
archive.

Synchronize the manually maintained RestWorld test scene into every compatible
1.21.1+ matrix instance and enable Continuity's bundled CTM packs:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\prism\Sync-PrismTestWorld.ps1
```

The source world and export bounds are declared in `restworld-test.json`. The
1.20.1 instance is deliberately excluded because the maintained source has
already been saved as DataVersion 3955 (Minecraft 1.21.1). Existing target
copies are left untouched unless `-Refresh` is supplied; refreshes are moved to
each instance's `.voxelbridge-world-backups` directory before replacement.

Build the current production JARs, export the RestWorld region through the
1.21.1+ Fabric/NeoForge Prism matrix, render two review cameras per result, and
open the combined Blender file:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\prism\Run-PrismRestWorldMatrix.ps1
```

Use `-Cases fabric-1.21.1-base` for a focused run. Outputs are collected under
`build/prism-restworld-runs/<run-id>/`; the hand-maintained source world is
opened only through a protected `RestWorld_1.20_VBTest` copy.

The generator only removes files recorded in its own
`.voxelbridge-managed.json` marker. It refuses to overwrite an existing Prism
instance that does not contain that marker. Third-party JARs are copied from
the existing local Modrinth profiles and are not committed to this repository.
