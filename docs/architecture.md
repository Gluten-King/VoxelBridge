# VoxelBridge architecture

VoxelBridge isolates Minecraft version churn at the runtime boundary:

```
core <- pipeline <- runtime/minecraft/<version> <- fabric|neoforge/<version>
```

## Dependency rules

- `core` contains scene IR, geometry and image algorithms, material semantics,
  and output writers. It never imports Minecraft or a loader API.
- `pipeline` owns export policy and orchestration. It consumes only the stable
  runtime contracts under `com.voxelbridge.pipeline` and never imports a game
  or loader API.
- `runtime/minecraft/<version>` translates one exact Minecraft API into the
  stable contracts. API renames and renderer rewrites stop here.
- Fabric and NeoForge targets own lifecycle, mixins, loader rendering hooks,
  configuration screens, and Mod integrations.

The build runs `verifyArchitectureBoundaries` to enforce the first two rules.
Runtime contracts are push-oriented and use borrowed primitive arrays so the
boundary does not add per-vertex allocation.

The supported-target list and every target's Java/resource source roots live in
`gradle/targets.json`. Individual loader builds consume that registry rather
than duplicating source-composition lists. `testFast` compiles every registered
target, while `verifyProductionJars` builds all eleven release
JARs and verifies that core/pipeline are present and verification classes are
not packaged. The full `GoldenTestController` is compiled into a separate
local-only harness JAR; release JARs contain only a generic, disabled
`ClientAutomationExtension` hook and the production gate rejects harness
classes. Harness timing begins immediately before `startExport`, excluding
world/client startup, and records export time plus sampled peak/delta heap use.
The pre-refactor evidence that actually exists in the repository
is hash-bound in `golden/baselines/c23476a.manifest.json`; absent measurements
are deliberately not invented.

## Migration status

`core` and `pipeline` are real ordinary Java 17 libraries and are already
composed into every production JAR. Texture/resource access, animation/PBR
policy, session state, normalized world/occlusion facts, region planning, and
glTF assembly are used by production code across the stable boundary.

The `BlockGeometrySource`/`QuadInput` and
`SpecialRenderSource`/`CapturedPrimitive` contracts are implemented and fake-
runtime tested, but they are not yet the sole production geometry entry. The
current block/entity/block-entity exporters remain version-owned runtime code
and continue writing the established IR path so this structural migration does
not silently change output before the missing versioned worlds and performance
baselines are captured. `SpecialRenderSource.EMPTY` is therefore not advertised
as a runtime capability. The next geometry cutover must be guarded by the full
production golden matrix; it must not be inferred from compilation alone.

Minecraft 1.21.11 now has a real shared exact-version source root at
`runtime/minecraft/1.21.11`; Fabric and NeoForge consume that same adapter.
The old `common/1.21.11` fork has been removed.

Minecraft 26.1.2 and 26.2 use a separate Java 25 Fabric runtime family under
`runtime/minecraft/shared/26.1.2-26.2`, with exact roots for renderer changes
between the two releases. These releases ship named classes, so their runtime
and production JARs deliberately skip the legacy mapping/remap stage.

The legacy `common` source tree has been removed. Byte-identical runtime code is
owned by explicit compatibility roots (`shared/all`, `shared/1.20.1-1.21.8`,
`shared/1.21.1-1.21.11`, and `shared/26.1.2-26.2`). Classes whose
Minecraft API differs remain in the 1.20.1, 1.21.1, 1.21.11, 26.1.2, or 26.2
exact adapter root; 1.21.4/1.21.8 reuse the 1.21.1
implementation only while it remains source-compatible. No target copies,
filters, or overrides common sources at build time.

The temporary static `Adapters` composition entrypoint remains only for the
production geometry path that is still protected by missing Golden/performance
baselines. `verifyAdapterMigrationRatchet` freezes its current call count: new
code must use constructor/session injection, and each geometry cutover must
lower the limit until the compatibility entrypoint can be deleted.

Every target also includes its own `runtime/minecraft/<exact version>` extension
root, even when that root currently contains no Java classes. When one member
of a compatible version family drifts, the affected class is split into that
exact root; pipeline and the unaffected adapters do not change.

The 1.20.1 API-specific exporters likewise live under
`runtime/minecraft/1.20.1`; its Fabric directory contains only loader-facing
entrypoints, bridges, Mixins, commands, and UI integration. Runtime roots are
build-checked against Fabric and NeoForge imports.

Each exact Minecraft version is also an independently compiled Loom
`java-library`, using that version's dependency lock and Java toolchain. Loader
targets no longer compile runtime source directories directly: Fabric embeds
the remapped runtime project JAR, while NeoForge shades the same project's named
artifact. This makes the shared Mojang adapter genuinely identical between the
two loaders and turns API drift into an exact-runtime compile failure.

Compatible NeoForge 1.21.8/1.21.11 lifecycle code lives in an explicit loader
shared source root. Exact-version replacements remain in each target directory;
the former `replacedPlatformSources` FQCN exclusion mechanism has been removed.

## Geometry ownership

The target architecture normalizes ordinary model output into `QuadInput` and
submitted renderer output into `CapturedPrimitive`, after which both paths use
the same pipeline policies. During the protected migration window, production
ordinary and special rendering remains in exact-runtime exporters; the stable
ports run in contract tests and are available for one subsystem cutover at a
time. Texture/resource reads and non-solid coverage policy already use the
stable ports in production.

Minecraft reports facts such as vanilla face visibility, face coverage, final
texture binding, and blend state. Pipeline decides VoxelBridge policies such as
selection-boundary preservation, duplicate collapse, inset, and output material
mode. Version comparisons are forbidden in pipeline; adapters advertise
`RuntimeCapabilities` instead.

## Session lifetime

All mutable caches and runtime overrides belong to an `ExportSession`. Static
renderer state, ThreadLocal override maps, and cross-export caches are legacy
patterns and must be removed as their subsystem migrates.

The UI may retain a single lightweight pointer to the active session for HUD
and selection rendering. The pointed-to maps, counters, texture caches, atlas
locators, plane-offset trackers, and deduplication sets are session-owned and
are released when the export closes.

Client preferences are read exactly once when the session is created. Core
writers and hot export paths receive immutable options and must never read the
mutable preference store directly. Runtime diagnostics are routed into the
structured probe stream through the session.

## Output schema

Every generated glTF advertises `asset.extras["voxelbridge:schemaVersion"]`.
Increment it only when public output semantics change, not for internal class
or package moves. BIRIS/linker-only attributes are not part of the main schema.
