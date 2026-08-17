[CmdletBinding()]
param(
    [string]$PrismRoot = 'D:\PrismLauncher-Windows-MinGW-w64-Portable-11.0.3',
    [string]$ModrinthProfiles = 'D:\ModrinthApp\profiles',
    [string]$RepositoryRoot = '',
    [string]$Definition = '',
    [string]$Blender = 'F:\Program Files\Steam\steamapps\common\Blender\blender.exe',
    [string[]]$Cases = @(),
    [switch]$SkipBuild,
    [switch]$SkipBlender,
    [ValidateSet(128, 256, 512, 1024, 2048, 4096, 8192)]
    [int]$AtlasSize = 8192,
    [int]$TimeoutSeconds = 900
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = (Resolve-Path (Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) '..\..')).Path
}
if ([string]::IsNullOrWhiteSpace($Definition)) {
    $Definition = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) 'restworld-test.json'
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )

    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        [System.IO.Directory]::CreateDirectory($parent) | Out-Null
    }
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
}

function Set-CfgValue {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][System.Collections.Generic.List[string]]$Lines,
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value
    )

    # QSettings keys belong to the section preceding them.  Appending after
    # Prism's [UI] section creates a perfectly plausible looking duplicate
    # that Prism does not read as a launch setting.  Remove every stale copy
    # and insert the canonical value into [General].
    for ($index = $Lines.Count - 1; $index -ge 0; $index--) {
        if ($Lines[$index].StartsWith($Key + '=', [System.StringComparison]::Ordinal)) {
            $Lines.RemoveAt($index)
        }
    }

    $insertAt = $Lines.Count
    $generalAt = $Lines.IndexOf('[General]')
    if ($generalAt -ge 0) {
        for ($index = $generalAt + 1; $index -lt $Lines.Count; $index++) {
            if ($Lines[$index].StartsWith('[', [System.StringComparison]::Ordinal)) {
                $insertAt = $index
                break
            }
        }
    }
    $Lines.Insert($insertAt, "$Key=$Value")
}

function Get-CfgState {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][System.Collections.Generic.List[string]]$Lines,
        [Parameter(Mandatory = $true)][string[]]$Keys
    )

    $state = [ordered]@{}
    foreach ($key in $Keys) {
        $entry = $null
        foreach ($line in $Lines) {
            if ($line.StartsWith($key + '=', [System.StringComparison]::Ordinal)) {
                $entry = $line.Substring($key.Length + 1)
                break
            }
        }
        $state[$key] = [ordered]@{ exists = $null -ne $entry; value = $entry }
    }
    return $state
}

function Remove-StaleGoldenJvmArgs {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][System.Collections.Generic.List[string]]$Lines,
        [Parameter(Mandatory = $true)][string]$InstanceId
    )

    $stale = $false
    foreach ($line in $Lines) {
        if ($line.StartsWith('JvmArgs=', [System.StringComparison]::Ordinal) -and
            ($line.Contains('-Dvoxelbridge.golden.') -or
             $line.Contains('-Dvoxelbridge.clientAutomationClass='))) {
            $stale = $true
            break
        }
    }
    if (-not $stale) {
        return
    }

    Write-Warning "Removing stale VoxelBridge automation JVM arguments from $InstanceId"
    Set-CfgValue -Lines $Lines -Key 'OverrideJavaArgs' -Value 'false'
    Set-CfgValue -Lines $Lines -Key 'JvmArgs' -Value ''
}

function Restore-CfgState {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$State
    )

    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $lines.Add($line)
    }
    foreach ($key in $State.Keys) {
        for ($index = $lines.Count - 1; $index -ge 0; $index--) {
            if ($lines[$index].StartsWith($key + '=', [System.StringComparison]::Ordinal)) {
                $lines.RemoveAt($index)
            }
        }
        if ($State[$key].exists) {
            Set-CfgValue -Lines $lines -Key $key -Value $State[$key].value
        }
    }
    Write-Utf8NoBom -Path $Path -Text (($lines -join [Environment]::NewLine) + [Environment]::NewLine)
}

function ConvertTo-QSettingsString {
    param([Parameter(Mandatory = $true)][string]$Value)

    # JvmArgs is a scalar string. Quoting the complete value is essential:
    # otherwise QSettings interprets coordinate commas as a QStringList and
    # Prism's scalar lookup silently yields no custom JVM arguments.
    return '"' + $Value.Replace('\', '\\').Replace('"', '\"') + '"'
}

function Set-Option {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][System.Collections.Generic.List[string]]$Lines,
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$Value
    )

    for ($index = 0; $index -lt $Lines.Count; $index++) {
        if ($Lines[$index].StartsWith($Key + ':', [System.StringComparison]::Ordinal)) {
            $Lines[$index] = "$Key`:$Value"
            return
        }
    }
    $Lines.Add("$Key`:$Value") | Out-Null
}

function Get-EnabledResourcePacks {
    param([Parameter(Mandatory = $true)][string]$OptionsPath)

    foreach ($line in [System.IO.File]::ReadAllLines($OptionsPath)) {
        if ($line.StartsWith('resourcePacks:', [System.StringComparison]::Ordinal)) {
            [string[]]$packs = $line.Substring('resourcePacks:'.Length) | ConvertFrom-Json
            if ($packs.Count -eq 0) {
                throw "No enabled resource packs were found in $OptionsPath"
            }
            return $packs
        }
    }
    throw "resourcePacks was not found in $OptionsPath"
}

function Get-CleanVoxelBridgeConfig {
    param([Parameter(Mandatory = $true)][string]$ConfigPath)

    if (-not (Test-Path -LiteralPath $ConfigPath)) {
        throw "VoxelBridge config was not found: $ConfigPath"
    }
    $config = [System.IO.File]::ReadAllText($ConfigPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    if ($null -eq $config.PSObject.Properties['vanillaRandomTransformEnabled'] -or
        $config.vanillaRandomTransformEnabled -ne $true) {
        throw "vanillaRandomTransformEnabled must be true in the source config: $ConfigPath"
    }
    # These keys belonged to the B-Iris experiment and must not propagate into pure-main runs.
    $config.PSObject.Properties.Remove('lightmapExportEnabled')
    $config.PSObject.Properties.Remove('materialIdentityMode')
    return ($config | ConvertTo-Json -Depth 10)
}

function Install-VoxelBridgeConfig {
    param(
        [Parameter(Mandatory = $true)][string]$InstancePath,
        [Parameter(Mandatory = $true)][string]$ConfigText
    )

    $configPath = Join-Path $InstancePath '.minecraft\config\voxelbridge.json'
    Write-Utf8NoBom -Path $configPath -Text ($ConfigText + [Environment]::NewLine)
}

function Restore-VoxelBridgeConfig {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowNull()][string]$OriginalText,
        [Parameter(Mandatory = $true)][bool]$OriginallyExisted
    )

    if ($OriginallyExisted) {
        Write-Utf8NoBom -Path $Path -Text $OriginalText
    }
    elseif (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path
    }
}

function Get-WorldSceneFingerprint {
    param(
        [Parameter(Mandatory = $true)][string]$WorldPath,
        [Parameter(Mandatory = $true)]$Scene
    )

    $paths = [System.Collections.Generic.List[string]]::new()
    $paths.Add('level.dat')
    $minRegionX = [int][Math]::Floor([double]$Scene.min[0] / 512.0)
    $maxRegionX = [int][Math]::Floor([double]$Scene.max[0] / 512.0)
    $minRegionZ = [int][Math]::Floor([double]$Scene.min[2] / 512.0)
    $maxRegionZ = [int][Math]::Floor([double]$Scene.max[2] / 512.0)
    foreach ($kind in @('region', 'entities', 'poi')) {
        for ($regionX = $minRegionX; $regionX -le $maxRegionX; $regionX++) {
            for ($regionZ = $minRegionZ; $regionZ -le $maxRegionZ; $regionZ++) {
                $relativePath = "$kind\r.$regionX.$regionZ.mca"
                if (Test-Path -LiteralPath (Join-Path $WorldPath $relativePath)) {
                    $paths.Add($relativePath)
                }
            }
        }
    }
    $evidence = foreach ($relativePath in ($paths | Sort-Object -Unique)) {
        $absolutePath = Join-Path $WorldPath $relativePath
        "$($relativePath.Replace('\', '/')):$((Get-FileHash -LiteralPath $absolutePath -Algorithm SHA256).Hash.ToLowerInvariant())"
    }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes(($evidence -join "`n"))
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function Ensure-Options {
    param(
        [Parameter(Mandatory = $true)][string]$InstanceId,
        [Parameter(Mandatory = $true)][bool]$Ctm
    )

    $instancePath = Join-Path $instancesRoot $InstanceId
    $optionsPath = Join-Path $instancePath '.minecraft\options.txt'
    if (-not (Test-Path -LiteralPath $optionsPath)) {
        $instanceDefinition = $instanceDefinitions.instances | Where-Object { $_.id -eq $InstanceId } | Select-Object -First 1
        if ($null -eq $instanceDefinition) {
            throw "No instance definition was found for $InstanceId"
        }
        $template = Join-Path (Join-Path $ModrinthProfiles $instanceDefinition.sourceProfile) 'options.txt'
        if (-not (Test-Path -LiteralPath $template)) {
            throw "No options template was found for $InstanceId at $template"
        }
        Copy-Item -LiteralPath $template -Destination $optionsPath
    }

    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in [System.IO.File]::ReadAllLines($optionsPath)) {
        $lines.Add($line)
    }
    Set-Option -Lines $lines -Key 'narrator' -Value '0'
    Set-Option -Lines $lines -Key 'onboardAccessibility' -Value 'false'
    Set-Option -Lines $lines -Key 'incompatibleResourcePacks' -Value '[]'
    $enabledPacks = if ($Ctm) {
        @($sourceResourcePacks)
    } else {
        @($sourceResourcePacks | Where-Object {
            $_ -ne 'fabric' -and -not $_.StartsWith('continuity:', [System.StringComparison]::Ordinal)
        })
    }
    Set-Option -Lines $lines -Key 'resourcePacks' -Value (ConvertTo-Json -Compress -InputObject @($enabledPacks))
    Write-Utf8NoBom -Path $optionsPath -Text (($lines -join [Environment]::NewLine) + [Environment]::NewLine)
}

function Test-JarEntry {
    param(
        [Parameter(Mandatory = $true)][string]$Jar,
        [Parameter(Mandatory = $true)][string]$Entry
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        return $null -ne ($archive.Entries | Where-Object {
            $_.FullName -eq $Entry
        } | Select-Object -First 1)
    }
    finally {
        $archive.Dispose()
    }
}

function Install-ProductionJar {
    param(
        [Parameter(Mandatory = $true)]$Case,
        [Parameter(Mandatory = $true)][string]$RunId
    )

    $builtJar = Join-Path $RepositoryRoot $Case.jar
    if (-not (Test-Path -LiteralPath $builtJar)) {
        throw "Built production JAR does not exist: $builtJar"
    }
    if (Test-JarEntry -Jar $builtJar -Entry 'com/voxelbridge/verification/client/GoldenTestController.class') {
        throw "Built production JAR unexpectedly contains local GoldenTestController: $builtJar"
    }

    $instancePath = Join-Path $instancesRoot $Case.instance
    $modsPath = Join-Path $instancePath '.minecraft\mods'
    [System.IO.Directory]::CreateDirectory($modsPath) | Out-Null
    $backupPath = Join-Path $instancePath ".voxelbridge-jar-backups\$RunId"
    foreach ($oldJar in Get-ChildItem -LiteralPath $modsPath -Filter 'VoxelBridge-*.jar' -File -ErrorAction SilentlyContinue) {
        [System.IO.Directory]::CreateDirectory($backupPath) | Out-Null
        Move-Item -LiteralPath $oldJar.FullName -Destination (Join-Path $backupPath $oldJar.Name)
    }
    $installedJar = Join-Path $modsPath (Split-Path -Leaf $builtJar)
    Copy-Item -LiteralPath $builtJar -Destination $installedJar

    $sourceHash = (Get-FileHash -LiteralPath $builtJar -Algorithm SHA256).Hash
    $installedHash = (Get-FileHash -LiteralPath $installedJar -Algorithm SHA256).Hash
    if ($sourceHash -ne $installedHash) {
        throw "Production JAR hash mismatch after installing $($Case.id)"
    }
    return $installedJar
}

function Install-GoldenHarness {
    param(
        [Parameter(Mandatory = $true)]$Case,
        [Parameter(Mandatory = $true)][string]$RunId
    )

    $fabric = ([string]$Case.target).StartsWith('fabric-', [System.StringComparison]::Ordinal)
    $classifier = if ($fabric) { 'fabric' } else { 'named' }
    $harnessDirectory = Join-Path $RepositoryRoot "build\runtime\minecraft\$($Case.minecraft)\libs"
    $harnessPattern = "VoxelBridge-golden-harness-$($Case.minecraft)-*-$classifier.jar"
    $candidates = @(Get-ChildItem -LiteralPath $harnessDirectory -Filter $harnessPattern -File)
    if ($candidates.Count -ne 1) {
        throw "Expected one local golden harness matching $harnessPattern in $harnessDirectory, found $($candidates.Count)"
    }
    $builtHarness = $candidates[0].FullName
    if (-not (Test-JarEntry -Jar $builtHarness -Entry 'com/voxelbridge/verification/client/GoldenTestController.class')) {
        throw "Local golden harness does not contain GoldenTestController: $builtHarness"
    }
    $metadataEntry = if ($fabric) { 'fabric.mod.json' } else { 'META-INF/neoforge.mods.toml' }
    if (-not (Test-JarEntry -Jar $builtHarness -Entry $metadataEntry)) {
        throw "Local golden harness does not contain $metadataEntry`: $builtHarness"
    }

    $instancePath = Join-Path $instancesRoot $Case.instance
    $modsPath = Join-Path $instancePath '.minecraft\mods'
    [System.IO.Directory]::CreateDirectory($modsPath) | Out-Null
    $backupPath = Join-Path $instancePath ".voxelbridge-jar-backups\$RunId"
    foreach ($oldJar in Get-ChildItem -LiteralPath $modsPath -Filter 'VoxelBridge-golden-harness-*.jar' -File -ErrorAction SilentlyContinue) {
        [System.IO.Directory]::CreateDirectory($backupPath) | Out-Null
        Move-Item -LiteralPath $oldJar.FullName -Destination (Join-Path $backupPath $oldJar.Name)
    }
    $installedHarness = Join-Path $modsPath $candidates[0].Name
    Copy-Item -LiteralPath $builtHarness -Destination $installedHarness
    if ((Get-FileHash -LiteralPath $builtHarness -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $installedHarness -Algorithm SHA256).Hash) {
        throw "Golden harness hash mismatch after installing $($Case.id)"
    }
    return $installedHarness
}

function Close-PrismLauncher {
    param([int]$WaitSeconds = 20)

    $launcherPath = [System.IO.Path]::GetFullPath($launcherExe)
    $running = @(Get-Process -Name 'prismlauncher' -ErrorAction SilentlyContinue | Where-Object {
        $_.Path -and [System.IO.Path]::GetFullPath($_.Path) -eq $launcherPath
    })
    foreach ($process in $running) {
        $null = $process.CloseMainWindow()
    }
    $deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $remaining = @(Get-Process -Name 'prismlauncher' -ErrorAction SilentlyContinue | Where-Object {
            $_.Path -and [System.IO.Path]::GetFullPath($_.Path) -eq $launcherPath
        })
        if ($remaining.Count -eq 0) {
            return
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'Prism Launcher did not close cleanly. Close it manually and run again.'
}

function Close-MinecraftInstance {
    param(
        [Parameter(Mandatory = $true)][string]$InstanceId,
        [int]$WaitSeconds = 30
    )

    $escapedInstance = [regex]::Escape("instances/$InstanceId/")
    $processIds = @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe' or Name='java.exe'" |
        Where-Object { $_.CommandLine -replace '\\', '/' -match $escapedInstance } |
        ForEach-Object { $_.ProcessId })
    foreach ($processId in $processIds) {
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if ($null -ne $process -and $process.MainWindowHandle -ne 0) {
            $null = $process.CloseMainWindow()
        }
    }
    if ($processIds.Count -gt 0) {
        Wait-Process -Id $processIds -Timeout $WaitSeconds -ErrorAction SilentlyContinue
    }
}

function Ensure-SourceRunWorld {
    param([Parameter(Mandatory = $true)][string]$InstanceId)

    if ($InstanceId -ne $definitionData.world.sourceInstance) {
        return $definitionData.world.folder
    }

    $instancePath = Join-Path $instancesRoot $InstanceId
    $sourceWorld = Join-Path $instancePath ".minecraft\saves\$($definitionData.world.folder)"
    $runWorldName = if ($null -ne $definitionData.world.PSObject.Properties['automationFolder']) {
        [string]$definitionData.world.automationFolder
    } else {
        "$($definitionData.world.folder)_Automation"
    }
    $runWorld = Join-Path $instancePath ".minecraft\saves\$runWorldName"
    $sourceHash = (Get-FileHash -LiteralPath (Join-Path $sourceWorld 'level.dat') -Algorithm SHA256).Hash.ToLowerInvariant()
    $sourceFingerprint = Get-WorldSceneFingerprint -WorldPath $sourceWorld -Scene $scene
    $markerPath = Join-Path $runWorld '.voxelbridge-automation-copy.json'
    $refreshRunWorld = $true
    if (Test-Path -LiteralPath $markerPath) {
        $marker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
        $refreshRunWorld = $null -eq $marker.PSObject.Properties['sourceFingerprint'] -or
            $marker.sourceFingerprint -ne $sourceFingerprint
    }
    if ($refreshRunWorld) {
        if (Test-Path -LiteralPath $runWorld) {
            $backupRoot = Join-Path $instancePath '.voxelbridge-world-backups'
            [System.IO.Directory]::CreateDirectory($backupRoot) | Out-Null
            Move-Item -LiteralPath $runWorld -Destination (Join-Path $backupRoot "$runWorldName.$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))")
        }
        [System.IO.Directory]::CreateDirectory($runWorld) | Out-Null
        Write-Host "Creating protected run copy for the hand-maintained source world ..."
        & robocopy.exe $sourceWorld $runWorld /E /XJ /COPY:DAT /DCOPY:DAT /R:1 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
        if ($LASTEXITCODE -gt 7) {
            throw "Could not create protected source-world run copy (robocopy exit $LASTEXITCODE)"
        }
        Write-Utf8NoBom -Path (Join-Path $runWorld '.voxelbridge-automation-copy.json') -Text (([ordered]@{
            source = $definitionData.world.folder
            createdAtUtc = [DateTime]::UtcNow.ToString('o')
            levelDatSha256 = $sourceHash
            sourceFingerprint = $sourceFingerprint
        } | ConvertTo-Json) + [Environment]::NewLine)
    }
    return $runWorldName
}

function Wait-ForResult {
    param(
        [Parameter(Mandatory = $true)][string]$ResultFile,
        [Parameter(Mandatory = $true)][string]$CaseId,
        [Parameter(Mandatory = $true)][string]$InstanceId,
        [Parameter(Mandatory = $true)][System.Diagnostics.Process]$LauncherProcess,
        [Parameter(Mandatory = $true)][int]$Timeout
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($Timeout)
    $startupDeadline = [DateTime]::UtcNow.AddSeconds(20)
    $nextUpdate = [DateTime]::UtcNow.AddSeconds(15)
    $sawMinecraft = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $ResultFile) {
            return Get-Content -LiteralPath $ResultFile -Raw | ConvertFrom-Json
        }
        $escapedInstance = [regex]::Escape((Join-Path $instancesRoot $InstanceId).Replace('\', '/'))
        $minecraftRunning = $null -ne (Get-CimInstance Win32_Process -Filter "Name='javaw.exe' or Name='java.exe'" |
            Where-Object { $_.CommandLine -replace '\\', '/' -match $escapedInstance } |
            Select-Object -First 1)
        $sawMinecraft = $sawMinecraft -or $minecraftRunning
        if (-not $minecraftRunning -and
            ($sawMinecraft -or ([DateTime]::UtcNow -ge $startupDeadline -and $LauncherProcess.HasExited))) {
            $exitDetail = if ($LauncherProcess.HasExited) { " (launcher exit $($LauncherProcess.ExitCode))" } else { '' }
            throw "$CaseId Minecraft process exited before producing result.json$exitDetail"
        }
        if ([DateTime]::UtcNow -ge $nextUpdate) {
            Write-Host "Waiting for $CaseId export ..."
            $nextUpdate = [DateTime]::UtcNow.AddSeconds(15)
        }
        Start-Sleep -Seconds 1
    }
    throw "$CaseId did not produce result.json within $Timeout seconds"
}

function Copy-GltfBundle {
    param(
        [Parameter(Mandatory = $true)][string]$Gltf,
        [Parameter(Mandatory = $true)][string]$CaseRoot
    )

    if (-not (Test-Path -LiteralPath $Gltf)) {
        throw "Export result points to a missing glTF: $Gltf"
    }
    $sourceDirectory = Split-Path -Parent $Gltf
    $destination = Join-Path $CaseRoot 'gltf'
    [System.IO.Directory]::CreateDirectory($destination) | Out-Null
    & robocopy.exe $sourceDirectory $destination /E /XJ /COPY:DAT /DCOPY:DAT /R:1 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -gt 7) {
        throw "Could not collect glTF bundle for $Gltf (robocopy exit $LASTEXITCODE)"
    }
    return Join-Path $destination (Split-Path -Leaf $Gltf)
}

function Assert-GltfMaterialQuads {
    param(
        [Parameter(Mandatory = $true)][string]$Gltf,
        [Parameter(Mandatory = $true)]$Expected
    )

    $document = [System.IO.File]::ReadAllText($Gltf, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    $counts = @{}
    foreach ($mesh in @($document.meshes)) {
        foreach ($primitive in @($mesh.primitives)) {
            if ($null -eq $primitive.PSObject.Properties['material']) {
                continue
            }
            $mode = if ($null -ne $primitive.PSObject.Properties['mode']) { [int]$primitive.mode } else { 4 }
            if ($mode -ne 4) {
                throw "Material quad assertion requires TRIANGLES primitives; found mode $mode in $Gltf"
            }
            $materialIndex = [int]$primitive.material
            $materialName = [string]$document.materials[$materialIndex].name
            $indexCount = if ($null -ne $primitive.PSObject.Properties['indices']) {
                [int]$document.accessors[[int]$primitive.indices].count
            } else {
                [int]$document.accessors[[int]$primitive.attributes.POSITION].count
            }
            if (($indexCount % 6) -ne 0) {
                throw "Material $materialName has $indexCount triangle indices, which is not whole quads"
            }
            if (-not $counts.ContainsKey($materialName)) {
                $counts[$materialName] = 0
            }
            $counts[$materialName] += [int]($indexCount / 6)
        }
    }

    $actual = [ordered]@{}
    foreach ($expectation in $Expected.PSObject.Properties) {
        $materialName = $expectation.Name
        $expectedCount = [int]$expectation.Value
        $actualCount = if ($counts.ContainsKey($materialName)) { [int]$counts[$materialName] } else { 0 }
        if ($actualCount -ne $expectedCount) {
            throw "Material $materialName has $actualCount quads in $Gltf; expected $expectedCount"
        }
        $actual[$materialName] = $actualCount
    }
    return $actual
}

function Read-GltfFloatAccessor {
    param(
        [Parameter(Mandatory = $true)]$Document,
        [Parameter(Mandatory = $true)][string]$GltfDirectory,
        [Parameter(Mandatory = $true)][int]$AccessorIndex
    )

    $accessor = $Document.accessors[$AccessorIndex]
    if ([int]$accessor.componentType -ne 5126) { throw "Accessor $AccessorIndex is not FLOAT" }
    $componentCount = switch ([string]$accessor.type) {
        'SCALAR' { 1 }
        'VEC2' { 2 }
        'VEC3' { 3 }
        'VEC4' { 4 }
        default { throw "Unsupported accessor type $($accessor.type)" }
    }
    $view = $Document.bufferViews[[int]$accessor.bufferView]
    $bufferPath = Join-Path $GltfDirectory ([string]$Document.buffers[[int]$view.buffer].uri)
    $bytes = [System.IO.File]::ReadAllBytes($bufferPath)
    $viewOffset = if ($null -ne $view.PSObject.Properties['byteOffset']) { [int]$view.byteOffset } else { 0 }
    $accessorOffset = if ($null -ne $accessor.PSObject.Properties['byteOffset']) { [int]$accessor.byteOffset } else { 0 }
    $stride = if ($null -ne $view.PSObject.Properties['byteStride']) { [int]$view.byteStride } else { $componentCount * 4 }
    $values = [System.Collections.Generic.List[float]]::new()
    $start = $viewOffset + $accessorOffset
    for ($element = 0; $element -lt [int]$accessor.count; $element++) {
        for ($component = 0; $component -lt $componentCount; $component++) {
            $values.Add([System.BitConverter]::ToSingle(
                $bytes, $start + $element * $stride + $component * 4))
        }
    }
    return ,$values.ToArray()
}

function Assert-GltfNonWhiteColormapMaterials {
    param(
        [Parameter(Mandatory = $true)][string]$Gltf,
        [Parameter(Mandatory = $true)][string[]]$Expected
    )

    [void][System.Reflection.Assembly]::LoadWithPartialName('System.Drawing')
    $document = [System.IO.File]::ReadAllText($Gltf, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    $directory = Split-Path -Parent $Gltf
    $actual = [ordered]@{}
    foreach ($materialName in $Expected) {
        $foundTint = $false
        foreach ($node in @($document.nodes)) {
            if ([string]$node.name -ne $materialName -or $null -eq $node.PSObject.Properties['mesh']) { continue }
            foreach ($primitive in @($document.meshes[[int]$node.mesh].primitives)) {
                $material = $document.materials[[int]$primitive.material]
                $extras = $material.extras
                if ($null -eq $extras -or
                    $null -eq $extras.PSObject.Properties['voxelbridge:colormapTextures'] -or
                    $null -eq $extras.PSObject.Properties['voxelbridge:colormapUV']) { continue }
                $attributeName = "TEXCOORD_$([int]$extras.'voxelbridge:colormapUV')"
                if ($null -eq $primitive.attributes.PSObject.Properties[$attributeName]) { continue }
                $uvs = Read-GltfFloatAccessor -Document $document -GltfDirectory $directory `
                    -AccessorIndex ([int]$primitive.attributes.$attributeName)
                $imageIndices = @($extras.'voxelbridge:colormapTextures')
                $bitmaps = @{}
                try {
                    for ($index = 0; $index + 1 -lt $uvs.Count; $index += 2) {
                        $u = [double]$uvs[$index]
                        $v = [double]$uvs[$index + 1]
                        $tileU = [Math]::Floor($u)
                        $tileV = [Math]::Floor($v)
                        $page = [int]($tileV * 10 + $tileU)
                        if ($page -lt 0 -or $page -ge $imageIndices.Count) { continue }
                        if (-not $bitmaps.ContainsKey($page)) {
                            $image = $document.images[[int]$imageIndices[$page]]
                            $bitmaps[$page] = [System.Drawing.Bitmap]::new(
                                (Join-Path $directory ([string]$image.uri)))
                        }
                        $bitmap = $bitmaps[$page]
                        $x = [Math]::Min($bitmap.Width - 1, [Math]::Max(0,
                            [Math]::Floor(($u - $tileU) * $bitmap.Width)))
                        $y = [Math]::Min($bitmap.Height - 1, [Math]::Max(0,
                            [Math]::Floor(($v - $tileV) * $bitmap.Height)))
                        $pixel = $bitmap.GetPixel([int]$x, [int]$y)
                        if ($pixel.A -gt 0 -and ($pixel.R -ne 255 -or $pixel.G -ne 255 -or $pixel.B -ne 255)) {
                            $foundTint = $true
                            break
                        }
                    }
                } finally {
                    foreach ($bitmap in $bitmaps.Values) { $bitmap.Dispose() }
                }
                if ($foundTint) { break }
            }
            if ($foundTint) { break }
        }
        if (-not $foundTint) {
            throw "Material $materialName does not sample a non-white colormap pixel in $Gltf"
        }
        $actual[$materialName] = $true
    }
    return $actual
}

function Assert-GltfNodeMinimumVertices {
    param(
        [Parameter(Mandatory = $true)][string]$Gltf,
        [Parameter(Mandatory = $true)]$Expected
    )

    $document = [System.IO.File]::ReadAllText($Gltf, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    $actual = [ordered]@{}
    foreach ($expectation in $Expected.PSObject.Properties) {
        $nodeName = $expectation.Name
        $minimum = [int]$expectation.Value
        $vertexCount = 0
        foreach ($node in @($document.nodes)) {
            $actualNodeName = [string]$node.name
            $isSemanticChild = $actualNodeName.StartsWith("${nodeName}__", [System.StringComparison]::Ordinal)
            if (($actualNodeName -ne $nodeName -and -not $isSemanticChild) -or
                $null -eq $node.PSObject.Properties['mesh']) {
                continue
            }
            $mesh = $document.meshes[[int]$node.mesh]
            foreach ($primitive in @($mesh.primitives)) {
                if ($null -ne $primitive.attributes.PSObject.Properties['POSITION']) {
                    $vertexCount += [int]$document.accessors[[int]$primitive.attributes.POSITION].count
                }
            }
        }
        if ($vertexCount -lt $minimum) {
            throw "Node $nodeName has $vertexCount vertices in $Gltf; expected at least $minimum"
        }
        $actual[$nodeName] = $vertexCount
    }
    return $actual
}

function Assert-GltfNodePixelAlignedUvs {
    param(
        [Parameter(Mandatory = $true)][string]$Gltf,
        [Parameter(Mandatory = $true)][string[]]$Expected,
        [double]$TolerancePixels = 0.01
    )

    [void][System.Reflection.Assembly]::LoadWithPartialName('System.Drawing')
    $document = [System.IO.File]::ReadAllText($Gltf, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    $directory = Split-Path -Parent $Gltf
    $actual = [ordered]@{}
    foreach ($nodeName in $Expected) {
        $coordinateCount = 0
        $maxPixelError = 0.0
        foreach ($node in @($document.nodes)) {
            $actualNodeName = [string]$node.name
            $isSemanticChild = $actualNodeName.StartsWith("${nodeName}__", [System.StringComparison]::Ordinal)
            if (($actualNodeName -ne $nodeName -and -not $isSemanticChild) -or
                $null -eq $node.PSObject.Properties['mesh']) { continue }

            foreach ($primitive in @($document.meshes[[int]$node.mesh].primitives)) {
                if ($null -eq $primitive.attributes.PSObject.Properties['TEXCOORD_0'] -or
                    $null -eq $primitive.PSObject.Properties['material']) { continue }
                $material = $document.materials[[int]$primitive.material]
                $baseColor = $material.pbrMetallicRoughness.baseColorTexture
                if ($null -eq $baseColor -or $null -eq $baseColor.PSObject.Properties['index']) { continue }
                $texture = $document.textures[[int]$baseColor.index]
                $image = $document.images[[int]$texture.source]
                $bitmap = [System.Drawing.Bitmap]::new((Join-Path $directory ([string]$image.uri)))
                try {
                    $uvs = Read-GltfFloatAccessor -Document $document -GltfDirectory $directory `
                        -AccessorIndex ([int]$primitive.attributes.TEXCOORD_0)
                    for ($index = 0; $index + 1 -lt $uvs.Count; $index += 2) {
                        $pixelU = [double]$uvs[$index] * $bitmap.Width
                        $pixelV = [double]$uvs[$index + 1] * $bitmap.Height
                        $errorU = [Math]::Abs($pixelU - [Math]::Round($pixelU))
                        $errorV = [Math]::Abs($pixelV - [Math]::Round($pixelV))
                        $maxPixelError = [Math]::Max($maxPixelError, [Math]::Max($errorU, $errorV))
                        $coordinateCount += 2
                    }
                } finally {
                    $bitmap.Dispose()
                }
            }
        }
        if ($coordinateCount -eq 0) {
            throw "Node $nodeName has no base-color UV coordinates in $Gltf"
        }
        if ($maxPixelError -gt $TolerancePixels) {
            throw "Node $nodeName has atlas UVs $($maxPixelError.ToString('0.0000')) pixels off the pixel grid in $Gltf; tolerance is $TolerancePixels"
        }
        $actual[$nodeName] = [ordered]@{
            coordinateCount = $coordinateCount
            maxPixelError = $maxPixelError
        }
    }
    return $actual
}

function Assert-GltfGlyphMaterial {
    param(
        [Parameter(Mandatory = $true)][string]$Gltf,
        [Parameter(Mandatory = $true)][string]$BaseNodeName
    )

    $document = [System.IO.File]::ReadAllText($Gltf, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    $glyphName = "${BaseNodeName}__glyph"
    $materialIndex = -1
    for ($i = 0; $i -lt @($document.materials).Count; $i++) {
        if ([string]$document.materials[$i].name -eq $glyphName) {
            $materialIndex = $i
            break
        }
    }
    if ($materialIndex -lt 0) {
        throw "Missing glyph material $glyphName in $Gltf"
    }

    $material = $document.materials[$materialIndex]
    if ([string]$material.alphaMode -ne 'BLEND') {
        throw "Glyph material $glyphName uses alphaMode=$($material.alphaMode) in $Gltf; expected BLEND"
    }
    if ($null -eq $material.extras -or $material.extras.'voxelbridge:glyph' -ne $true) {
        throw "Glyph material $glyphName is missing voxelbridge:glyph=true in $Gltf"
    }

    $vertexCount = 0
    $uvCount = 0
    foreach ($node in @($document.nodes)) {
        if ([string]$node.name -ne $glyphName -or $null -eq $node.PSObject.Properties['mesh']) {
            continue
        }
        $mesh = $document.meshes[[int]$node.mesh]
        foreach ($primitive in @($mesh.primitives)) {
            if ([int]$primitive.material -ne $materialIndex) {
                continue
            }
            if ($null -eq $primitive.attributes.PSObject.Properties['POSITION'] -or
                $null -eq $primitive.attributes.PSObject.Properties['TEXCOORD_0']) {
                throw "Glyph primitive $glyphName is missing POSITION or TEXCOORD_0 in $Gltf"
            }
            $vertexCount += [int]$document.accessors[[int]$primitive.attributes.POSITION].count
            $uvCount += [int]$document.accessors[[int]$primitive.attributes.TEXCOORD_0].count
        }
    }
    if ($vertexCount -le 0 -or $uvCount -ne $vertexCount) {
        throw "Glyph node $glyphName has vertices=$vertexCount UVs=$uvCount in $Gltf"
    }
    return [ordered]@{
        material = $glyphName
        alphaMode = 'BLEND'
        vertices = $vertexCount
        texcoord0 = $uvCount
    }
}

if (-not (Test-Path -LiteralPath $Definition)) {
    throw "Definition not found: $Definition"
}
$definitionData = [System.IO.File]::ReadAllText($Definition, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$instanceDefinitionsPath = Join-Path (Split-Path -Parent $Definition) 'instances.json'
$instanceDefinitions = [System.IO.File]::ReadAllText($instanceDefinitionsPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$instancesRoot = Join-Path $PrismRoot 'instances'
$launcherExe = Join-Path $PrismRoot 'prismlauncher.exe'
$scenarioFile = Join-Path (Split-Path -Parent $Definition) 'restworld-prepare.mcfunction'
$sourceMinecraftPath = Join-Path (Join-Path $instancesRoot $definitionData.world.sourceInstance) '.minecraft'
$sourceOptionsPath = Join-Path $sourceMinecraftPath 'options.txt'
$sourceConfigPath = Join-Path $sourceMinecraftPath 'config\voxelbridge.json'
$sourceResourcePacks = @(Get-EnabledResourcePacks -OptionsPath $sourceOptionsPath)
$sourceVoxelBridgeConfig = Get-CleanVoxelBridgeConfig -ConfigPath $sourceConfigPath
Write-Utf8NoBom -Path $sourceConfigPath -Text ($sourceVoxelBridgeConfig + [Environment]::NewLine)
foreach ($required in @($launcherExe, $scenarioFile)) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Required file not found: $required"
    }
}

$selectedCases = @($definitionData.matrix)
if ($Cases.Count -gt 0) {
    $selectedCases = @($selectedCases | Where-Object { $Cases -contains $_.id })
    $unknownCases = @($Cases | Where-Object { $_ -notin @($definitionData.matrix | ForEach-Object { $_.id }) })
    if ($unknownCases.Count -gt 0) {
        throw "Unknown case(s): $($unknownCases -join ', ')"
    }
}
if ($selectedCases.Count -eq 0) {
    throw 'No matrix cases were selected.'
}

$scene = $definitionData.scenes | Where-Object { $_.id -eq 'restworld_core' } | Select-Object -First 1
if ($null -eq $scene) {
    throw 'Scene restworld_core was not found in the definition.'
}

Close-PrismLauncher
if (-not $SkipBuild) {
    $tasks = @(
        $selectedCases | ForEach-Object { $_.buildTask }
        $selectedCases | ForEach-Object {
            if (([string]$_.target).StartsWith('fabric-', [System.StringComparison]::Ordinal)) {
                ":runtime:minecraft:mc-$($_.minecraft):remapGoldenHarnessJar"
            } else {
                ":runtime:minecraft:mc-$($_.minecraft):goldenHarnessJar"
            }
        }
    ) | Select-Object -Unique
    Write-Host "Building production JARs: $($tasks -join ', ')"
    & (Join-Path $RepositoryRoot 'gradlew.bat') @tasks
    if ($LASTEXITCODE -ne 0) {
        throw "Production JAR build failed with exit code $LASTEXITCODE"
    }
}

$runId = [DateTime]::UtcNow.ToString("yyyyMMdd'T'HHmmss'Z'")
$runRoot = Join-Path $RepositoryRoot "build\prism-restworld-runs\$runId"
[System.IO.Directory]::CreateDirectory($runRoot) | Out-Null
$caseResults = @()
$blenderItems = @()

foreach ($case in $selectedCases) {
    Write-Host ''
    Write-Host "=== $($case.id) ==="
    $caseRoot = Join-Path $runRoot $case.id
    [System.IO.Directory]::CreateDirectory($caseRoot) | Out-Null
    $instancePath = Join-Path $instancesRoot $case.instance
    if (-not (Test-Path -LiteralPath $instancePath)) {
        throw "Prism instance not found: $instancePath"
    }
    $worldId = Ensure-SourceRunWorld -InstanceId $case.instance
    $worldPath = Join-Path $instancePath ".minecraft\saves\$worldId"
    if (-not (Test-Path -LiteralPath (Join-Path $worldPath 'level.dat'))) {
        throw "Test world not found for $($case.id): $worldPath"
    }

    Ensure-Options -InstanceId $case.instance -Ctm ([bool]$case.ctm)
    $targetConfigPath = Join-Path $instancePath '.minecraft\config\voxelbridge.json'
    $targetConfigExisted = Test-Path -LiteralPath $targetConfigPath
    $targetConfigOriginal = if ($targetConfigExisted) {
        [System.IO.File]::ReadAllText($targetConfigPath, [System.Text.Encoding]::UTF8)
    } else {
        $null
    }
    Install-VoxelBridgeConfig -InstancePath $instancePath -ConfigText $sourceVoxelBridgeConfig
    $installedJar = Install-ProductionJar -Case $case -RunId $runId
    $installedHarness = Install-GoldenHarness -Case $case -RunId $runId
    $jarHash = (Get-FileHash -LiteralPath $installedJar -Algorithm SHA256).Hash.ToLowerInvariant()
    $harnessHash = (Get-FileHash -LiteralPath $installedHarness -Algorithm SHA256).Hash.ToLowerInvariant()
    $resultFile = Join-Path $caseRoot 'result.json'
    $instanceConfig = Join-Path $instancePath 'instance.cfg'
    $configLines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in [System.IO.File]::ReadAllLines($instanceConfig)) {
        $configLines.Add($line)
    }
    Remove-StaleGoldenJvmArgs -Lines $configLines -InstanceId $case.instance
    $managedKeys = @('OverrideJavaArgs', 'JvmArgs', 'QuitAfterGameStop', 'AutoCloseConsole', 'ShowConsoleOnError')
    $originalConfig = Get-CfgState -Lines $configLines -Keys $managedKeys

    $javaArguments = @(
        '-Dvoxelbridge.clientAutomationClass=com.voxelbridge.verification.client.GoldenTestController',
        '-Dvoxelbridge.golden.enabled=true',
        '-Dvoxelbridge.golden.requireProductionJar=true',
        "-Dvoxelbridge.golden.expectedJar=$($installedJar.Replace('\', '/'))",
        "-Dvoxelbridge.golden.scenarioFile=$($scenarioFile.Replace('\', '/'))",
        "-Dvoxelbridge.golden.resultFile=$($resultFile.Replace('\', '/'))",
        "-Dvoxelbridge.golden.minecraftVersion=$($case.minecraft)",
        "-Dvoxelbridge.golden.pos1=$($scene.pos1 -join ',')",
        "-Dvoxelbridge.golden.pos2=$($scene.pos2 -join ',')",
        '-Dvoxelbridge.golden.settleTicks=100',
        '-Dvoxelbridge.golden.exportThreadCount=16',
        '-Dvoxelbridge.golden.atlasMode=atlas',
        "-Dvoxelbridge.golden.atlasSize=$AtlasSize",
        '-Dvoxelbridge.golden.coordinateMode=centered',
        '-Dvoxelbridge.golden.autoStop=true',
        "-Dvoxelbridge.golden.timeoutSeconds=$TimeoutSeconds"
    )
    Set-CfgValue -Lines $configLines -Key 'OverrideJavaArgs' -Value 'true'
    Set-CfgValue -Lines $configLines -Key 'JvmArgs' -Value (ConvertTo-QSettingsString -Value ($javaArguments -join ' '))
    Set-CfgValue -Lines $configLines -Key 'QuitAfterGameStop' -Value 'true'
    Set-CfgValue -Lines $configLines -Key 'AutoCloseConsole' -Value 'true'
    Set-CfgValue -Lines $configLines -Key 'ShowConsoleOnError' -Value 'true'
    Write-Utf8NoBom -Path $instanceConfig -Text (($configLines -join [Environment]::NewLine) + [Environment]::NewLine)

    $result = $null
    try {
        Write-Host "Launching $($case.instance) / $worldId"
        $launcherStdout = Join-Path $caseRoot 'prism-stdout.log'
        $launcherStderr = Join-Path $caseRoot 'prism-stderr.log'
        $launcherProcess = Start-Process -FilePath $launcherExe `
            -ArgumentList @('--launch', $case.instance, '--world', $worldId) `
            -RedirectStandardOutput $launcherStdout `
            -RedirectStandardError $launcherStderr `
            -WindowStyle Hidden -PassThru
        $result = Wait-ForResult `
            -ResultFile $resultFile `
            -CaseId $case.id `
            -InstanceId $case.instance `
            -LauncherProcess $launcherProcess `
            -Timeout $TimeoutSeconds
    }
    finally {
        try {
            Close-MinecraftInstance -InstanceId $case.instance -WaitSeconds 30
            Close-PrismLauncher -WaitSeconds 30
        }
        finally {
            Restore-CfgState -Path $instanceConfig -State $originalConfig
            Restore-VoxelBridgeConfig `
                -Path $targetConfigPath `
                -OriginalText $targetConfigOriginal `
                -OriginallyExisted $targetConfigExisted
            if (Test-Path -LiteralPath $installedHarness) {
                [System.IO.File]::Delete($installedHarness)
            }
        }
    }

    $latestLog = Join-Path $instancePath '.minecraft\logs\latest.log'
    if (Test-Path -LiteralPath $latestLog) {
        Copy-Item -LiteralPath $latestLog -Destination (Join-Path $caseRoot 'latest.log')
    }
    if ($result.status -ne 'passed') {
        throw "$($case.id) export failed: $($result.error)"
    }
    if ($result.productionJarVerified -ne $true) {
        throw "$($case.id) did not verify its production JAR"
    }
    if ($result.jarSha256 -ne $jarHash) {
        throw "$($case.id) reported JAR hash $($result.jarSha256), expected $jarHash"
    }

    $collectedGltf = Copy-GltfBundle -Gltf $result.gltf -CaseRoot $caseRoot
    $materialQuads = $null
    if ($null -ne $case.PSObject.Properties['expectedMaterialQuads']) {
        $materialQuads = Assert-GltfMaterialQuads `
            -Gltf $collectedGltf `
            -Expected $case.expectedMaterialQuads
    }
    $nonWhiteColormapMaterials = $null
    if ($null -ne $case.PSObject.Properties['expectedNonWhiteColormapMaterials']) {
        $nonWhiteColormapMaterials = Assert-GltfNonWhiteColormapMaterials `
            -Gltf $collectedGltf `
            -Expected @($case.expectedNonWhiteColormapMaterials)
    }
    $nodeVertices = $null
    if ($null -ne $case.PSObject.Properties['expectedNodeMinimumVertices']) {
        $nodeVertices = Assert-GltfNodeMinimumVertices `
            -Gltf $collectedGltf `
            -Expected $case.expectedNodeMinimumVertices
    }
    $pixelAlignedUvNodes = $null
    if ($null -ne $case.PSObject.Properties['expectedPixelAlignedUvNodes']) {
        $pixelAlignedUvNodes = Assert-GltfNodePixelAlignedUvs `
            -Gltf $collectedGltf `
            -Expected @($case.expectedPixelAlignedUvNodes)
    }
    $glyphMaterial = $null
    if ($null -ne $case.PSObject.Properties['expectedNodeMinimumVertices'] -and
        $null -ne $case.expectedNodeMinimumVertices.PSObject.Properties['blockentity:minecraft:sign']) {
        $glyphMaterial = Assert-GltfGlyphMaterial `
            -Gltf $collectedGltf `
            -BaseNodeName 'blockentity:minecraft:sign'
    }
    $caseRecord = [ordered]@{
        id = $case.id
        instance = $case.instance
        target = $case.target
        minecraft = $case.minecraft
        ctm = [bool]$case.ctm
        status = $result.status
        durationMillis = $result.durationMillis
        jarSha256 = $jarHash
        harnessSha256 = $harnessHash
        gltf = $collectedGltf
        originalGltf = $result.gltf
    }
    if ($null -ne $materialQuads) {
        $caseRecord.materialQuads = $materialQuads
    }
    if ($null -ne $nonWhiteColormapMaterials) {
        $caseRecord.nonWhiteColormapMaterials = $nonWhiteColormapMaterials
    }
    if ($null -ne $nodeVertices) {
        $caseRecord.nodeVertices = $nodeVertices
    }
    if ($null -ne $pixelAlignedUvNodes) {
        $caseRecord.pixelAlignedUvNodes = $pixelAlignedUvNodes
    }
    if ($null -ne $glyphMaterial) {
        $caseRecord.glyphMaterial = $glyphMaterial
    }
    $caseResults += $caseRecord
    $blenderItems += [ordered]@{
        caseId = $case.id
        instance = $case.instance
        gltf = $collectedGltf
        outputDirectory = (Join-Path $caseRoot 'review')
        referenceDirectory = (Join-Path $RepositoryRoot "golden\references\restworld_core\$($case.id)")
        cameras = @(
            [ordered]@{ id = 'overview'; azimuth = 45; elevation = 35; margin = 1.2 },
            [ordered]@{ id = 'reverse'; azimuth = 225; elevation = 28; margin = 1.2 }
        )
    }
    Write-Host "PASS $($case.id): $collectedGltf"
}

$summary = [ordered]@{
    schemaVersion = 1
    runId = $runId
    createdAtUtc = [DateTime]::UtcNow.ToString('o')
    scene = [ordered]@{
        id = $scene.id
        dimension = $scene.dimension
        pos1 = @($scene.pos1)
        pos2 = @($scene.pos2)
        min = @($scene.min)
        max = @($scene.max)
    }
    resourcePacks = @($sourceResourcePacks)
    voxelBridgeConfigSha256 = (Get-FileHash -LiteralPath $sourceConfigPath -Algorithm SHA256).Hash.ToLowerInvariant()
    vanillaRandomTransformEnabled = $true
    atlasSize = $AtlasSize
    cases = $caseResults
}
Write-Utf8NoBom -Path (Join-Path $runRoot 'run.json') -Text (($summary | ConvertTo-Json -Depth 10) + [Environment]::NewLine)
$manifestPath = Join-Path $runRoot 'blender-manifest.json'
Write-Utf8NoBom -Path $manifestPath -Text ((ConvertTo-Json -InputObject @($blenderItems) -Depth 10) + [Environment]::NewLine)
Write-Utf8NoBom -Path (Join-Path $RepositoryRoot 'build\prism-restworld-runs\latest.txt') -Text ($runRoot + [Environment]::NewLine)

if (-not $SkipBlender) {
    if (-not (Test-Path -LiteralPath $Blender)) {
        throw "Blender executable not found: $Blender"
    }
    $renderScript = Join-Path $RepositoryRoot 'golden\blender\render_review.py'
    Write-Host ''
    Write-Host 'Rendering one Blender review per Prism instance ...'
    foreach ($item in $blenderItems) {
        $caseRoot = Split-Path -Parent ([string]$item.outputDirectory)
        $caseManifest = Join-Path $caseRoot 'blender-manifest.json'
        Write-Utf8NoBom -Path $caseManifest -Text ((ConvertTo-Json -InputObject @($item) -Depth 10) + [Environment]::NewLine)
        $reviewOutput = Join-Path $caseRoot 'blender-review'
        $blendName = "$($item.instance).blend"
        & $Blender --background --python $renderScript -- --manifest $caseManifest --output $reviewOutput --blend-name $blendName
        if ($LASTEXITCODE -ne 0) {
            throw "Blender review render failed for $($item.instance) with exit code $LASTEXITCODE"
        }
        $blendFile = Join-Path $reviewOutput $blendName
        if (-not (Test-Path -LiteralPath $blendFile)) {
            throw "Blender did not create $blendFile"
        }
        Start-Process -FilePath $Blender -ArgumentList @($blendFile)
        Write-Host "Opened Blender review: $blendFile"
    }
}

Write-Host ''
Write-Host "Matrix run complete: $runRoot"
