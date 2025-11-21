# VoxelBridge

Client-side NeoForge mod that exports Minecraft blocks, fluids, and block entities to glTF 2.0 with biome tinting and packed/individual texture workflows.

## Features
- Select a region in-world and export it to glTF.
- Supports blocks, fluids, and block entities with biome tinting.
- Choose individual textures or packed atlas (UDIM-style up to 8192).
- Centered or world-space coordinates, configurable atlas size, adjustable export threads.
- Timestamped export folders under `export/`.

## Requirements
- Minecraft 1.21.1
- NeoForge 21.1.x 
- Java 21

## Install
1) Download the built jar (or build it yourself, see below).
2) Drop it into `mods/` of your NeoForge 1.21.1 instance.
3) Launch the game; this is a client-side tool only

## Usage
### Default hotkeys
- Set pos1: Numpad 7 (`key.voxelbridge.pos1`)
- Set pos2: Numpad 9 (`key.voxelbridge.pos2`)
- Export selection: Numpad 5 (`key.voxelbridge.export`)
- Clear selection: Numpad 0 (`key.voxelbridge.clear`)

### Commands (`/voxelbridge` or `/vb`)
- `pos1`, `pos2`: Raycast the block you're looking at and set corners.
- `info`: Show current selection and export settings.
- `clear`: Clear selection preview.
- `atlas [individual|atlas]`: Switch texture packing mode (individual textures vs packed UDIM atlas).
- `atlassize <128|256|512|1024|2048|4096|8192>`: Set atlas tile size when atlas mode is used.
- `coords [centered|world]`: Center the model at origin or keep world coordinates.
- `threads <1-32>`: Set export thread count.
- `export`: Export current selection to glTF.

### Export output
- Files are written to `export/<timestamp>/` next to your game directory.

## Build / Develop
- Prereqs: JDK 21, Gradle wrapper included.
- Build: `./gradlew build`
- Dev client: `./gradlew runClient`
- Sources under `src/main/java`, resources under `src/main/resources` (including `assets/voxelbridge/logo.png` and lang files).

## License
MIT License. See `LICENSE` for details.
