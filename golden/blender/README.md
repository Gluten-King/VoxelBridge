# Blender golden review

Configure Blender explicitly; the test system never downloads or changes it:

```powershell
$env:VOXELBRIDGE_BLENDER = 'C:\Program Files\Blender Foundation\Blender 5.0\blender.exe'
.\gradlew.bat renderGoldenReview
.\gradlew.bat reviewLatest
```

`renderGoldenReview` performs clean glTF imports, uses fixed Eevee, Standard
color management, lights, resolution and scenario cameras, then writes current,
reference, diff and heatmap PNGs beside each local run. All cases are retained
in `build/reports/golden/latest/review.blend` for interactive inspection.

The HTML report is read-only. Approval and rejection are always explicit Gradle
operations so opening a browser or `.blend` file cannot mutate a baseline.
