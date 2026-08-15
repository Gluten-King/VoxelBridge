# Runtime adapter troubleshooting

Start with the run manifest and structured probe stream. Confirm the production
JAR hash and target, then locate the first capability downgrade or fallback.

- Missing or wrong texture: inspect resource ID candidates, final renderer
  binding, sprite atlas bounds, dynamic readback, then atlas registration.
- Wrong UV: compare raw UV, normalized UV, sprite bounds, and atlas transform.
  Missing UV is valid for some masks/background primitives and is not by itself
  an error.
- Missing face: compare vanilla visibility, neighbor-loaded state, solid fact,
  face coverage rectangles, duplicate collapse, and nonsolid inset decisions.
- Entity or block entity absent: verify exact renderer selection, capture start
  and end, submitted primitive count, and final material state capability.
- Cross-scene-only failure: check for mutable static fields, ThreadLocal
  overrides, or caches not owned by `ExportSession`.

An unknown fact must retain geometry where possible and emit a diagnostic.
Do not fix a version regression by adding a Minecraft version check to
pipeline; extend the exact runtime adapter or, only for new render semantics,
add an explicit capability and contract test.

## Windows Loom cache contention

The exact runtime adapters and loader targets are separate Loom projects. On
Windows, an IDE Gradle daemon may temporarily hold Loom's shared Minecraft JAR
cache while the release matrix is configuring. If a matrix build reports a
locked JAR or a transient missing-mappings file, stop the IDE import or let it
finish, then run the release verification serially:

```powershell
.\gradlew.bat verifyProductionJars --no-parallel
```

Do not delete the Loom cache to work around an active lock. The production JAR
gate also inspects the embedded runtime class namespace: Fabric must contain an
intermediary-remapped runtime JAR, while NeoForge must contain named Mojang
classes.

Golden automation is supplied by a separate local harness JAR. If a production
client starts but no scenario runs, confirm the instance contains exactly one
`VoxelBridge-golden-harness-<version>` JAR and that the JVM property
`voxelbridge.clientAutomationClass` names
`com.voxelbridge.verification.client.GoldenTestController`. The release VoxelBridge JAR must
not contain that controller.
