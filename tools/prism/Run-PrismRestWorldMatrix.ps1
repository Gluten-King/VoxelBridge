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
        [Parameter(Mandatory = $true)][System.Collections.Generic.List[string]]$Lines,
        [Parameter(Mandatory = $true)][string]$Key,
        [Parameter(Mandatory = $true)][string]$Value
    )

    $replacement = "$Key=$Value"
    for ($index = 0; $index -lt $Lines.Count; $index++) {
        if ($Lines[$index].StartsWith($Key + '=', [System.StringComparison]::Ordinal)) {
            $Lines[$index] = $replacement
            return
        }
    }
    $Lines.Add($replacement) | Out-Null
}

function Get-CfgState {
    param(
        [Parameter(Mandatory = $true)][System.Collections.Generic.List[string]]$Lines,
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
            $lines.Add("$key=$($State[$key].value)") | Out-Null
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
        [Parameter(Mandatory = $true)][System.Collections.Generic.List[string]]$Lines,
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

function Test-GoldenController {
    param([Parameter(Mandatory = $true)][string]$Jar)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        return $null -ne ($archive.Entries | Where-Object {
            $_.FullName -eq 'com/voxelbridge/client/GoldenTestController.class'
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
    if (-not (Test-GoldenController -Jar $builtJar)) {
        throw "Built production JAR does not contain GoldenTestController: $builtJar"
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
        [Parameter(Mandatory = $true)][int]$Timeout
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($Timeout)
    $nextUpdate = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (Test-Path -LiteralPath $ResultFile) {
            return Get-Content -LiteralPath $ResultFile -Raw | ConvertFrom-Json
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
    $tasks = @($selectedCases | ForEach-Object { $_.buildTask } | Select-Object -Unique)
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
    Install-VoxelBridgeConfig -InstancePath $instancePath -ConfigText $sourceVoxelBridgeConfig
    $installedJar = Install-ProductionJar -Case $case -RunId $runId
    $jarHash = (Get-FileHash -LiteralPath $installedJar -Algorithm SHA256).Hash.ToLowerInvariant()
    $resultFile = Join-Path $caseRoot 'result.json'
    $instanceConfig = Join-Path $instancePath 'instance.cfg'
    $configLines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in [System.IO.File]::ReadAllLines($instanceConfig)) {
        $configLines.Add($line)
    }
    $managedKeys = @('OverrideJavaArgs', 'JvmArgs', 'QuitAfterGameStop', 'AutoCloseConsole', 'ShowConsoleOnError')
    $originalConfig = Get-CfgState -Lines $configLines -Keys $managedKeys

    $javaArguments = @(
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
        Start-Process -FilePath $launcherExe `
            -ArgumentList @('--launch', $case.instance, '--world', $worldId) `
            -RedirectStandardOutput $launcherStdout `
            -RedirectStandardError $launcherStderr `
            -WindowStyle Hidden | Out-Null
        $result = Wait-ForResult -ResultFile $resultFile -CaseId $case.id -Timeout $TimeoutSeconds
    }
    finally {
        try {
            Close-MinecraftInstance -InstanceId $case.instance -WaitSeconds 30
            Close-PrismLauncher -WaitSeconds 30
        }
        finally {
            Restore-CfgState -Path $instanceConfig -State $originalConfig
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
    $caseRecord = [ordered]@{
        id = $case.id
        instance = $case.instance
        target = $case.target
        minecraft = $case.minecraft
        ctm = [bool]$case.ctm
        status = $result.status
        durationMillis = $result.durationMillis
        jarSha256 = $jarHash
        gltf = $collectedGltf
        originalGltf = $result.gltf
    }
    if ($null -ne $materialQuads) {
        $caseRecord.materialQuads = $materialQuads
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
