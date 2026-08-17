[CmdletBinding()]
param(
    [string]$PrismRoot = 'D:\PrismLauncher-Windows-MinGW-w64-Portable-11.0.3',
    [string]$Definition = '',
    [switch]$Refresh
)

$ErrorActionPreference = 'Stop'

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
    $config.PSObject.Properties.Remove('lightmapExportEnabled')
    $config.PSObject.Properties.Remove('materialIdentityMode')
    return ($config | ConvertTo-Json -Depth 10)
}

function Get-LevelDataVersion {
    param([Parameter(Mandatory = $true)][string]$LevelDat)

    $inputStream = [System.IO.File]::OpenRead($LevelDat)
    try {
        $gzip = New-Object System.IO.Compression.GZipStream(
            $inputStream,
            [System.IO.Compression.CompressionMode]::Decompress
        )
        try {
            $buffer = New-Object System.IO.MemoryStream
            $gzip.CopyTo($buffer)
            $bytes = $buffer.ToArray()
        }
        finally {
            $gzip.Dispose()
        }
    }
    finally {
        $inputStream.Dispose()
    }

    $needle = [System.Text.Encoding]::UTF8.GetBytes('DataVersion')
    for ($offset = 0; $offset -le $bytes.Length - $needle.Length - 4; $offset++) {
        $matched = $true
        for ($index = 0; $index -lt $needle.Length; $index++) {
            if ($bytes[$offset + $index] -ne $needle[$index]) {
                $matched = $false
                break
            }
        }
        if ($matched) {
            $valueOffset = $offset + $needle.Length
            $value = ([uint32]$bytes[$valueOffset] -shl 24) -bor
                     ([uint32]$bytes[$valueOffset + 1] -shl 16) -bor
                     ([uint32]$bytes[$valueOffset + 2] -shl 8) -bor
                     [uint32]$bytes[$valueOffset + 3]
            return [int]$value
        }
    }

    throw "DataVersion was not found in $LevelDat"
}

function Assert-WorldClosed {
    param([Parameter(Mandatory = $true)][string]$WorldPath)

    $lockPath = Join-Path $WorldPath 'session.lock'
    if (-not (Test-Path -LiteralPath $lockPath)) {
        return
    }

    try {
        $lockStream = [System.IO.File]::Open(
            $lockPath,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::ReadWrite,
            [System.IO.FileShare]::None
        )
        $lockStream.Dispose()
    }
    catch {
        throw "The source world appears to be open in Minecraft: $WorldPath"
    }
}

function Get-SceneRegionFiles {
    param(
        [Parameter(Mandatory = $true)][string]$WorldPath,
        [Parameter(Mandatory = $true)]$Scene
    )

    $minRegionX = [int][Math]::Floor([double]$Scene.min[0] / 512.0)
    $maxRegionX = [int][Math]::Floor([double]$Scene.max[0] / 512.0)
    $minRegionZ = [int][Math]::Floor([double]$Scene.min[2] / 512.0)
    $maxRegionZ = [int][Math]::Floor([double]$Scene.max[2] / 512.0)
    $files = @()

    foreach ($kind in @('region', 'entities', 'poi')) {
        for ($regionX = $minRegionX; $regionX -le $maxRegionX; $regionX++) {
            for ($regionZ = $minRegionZ; $regionZ -le $maxRegionZ; $regionZ++) {
                $path = Join-Path $WorldPath "$kind\r.$regionX.$regionZ.mca"
                if (Test-Path -LiteralPath $path) {
                    $files += [ordered]@{
                        path = $path.Substring($WorldPath.Length + 1).Replace('\', '/')
                        sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
                    }
                }
            }
        }
    }

    return $files
}

function Set-ResourcePacks {
    param(
        [Parameter(Mandatory = $true)][string]$InstancePath,
        [Parameter(Mandatory = $true)][string[]]$ResourcePacks,
        [Parameter(Mandatory = $true)][string]$TemplateOptions,
        [Parameter(Mandatory = $true)][bool]$RequireContinuity
    )

    $minecraftPath = Join-Path $InstancePath '.minecraft'
    if ($RequireContinuity) {
        $continuityJar = Get-ChildItem -LiteralPath (Join-Path $minecraftPath 'mods') -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like 'continuity-*.jar' } |
            Select-Object -First 1
        if ($null -eq $continuityJar) {
            throw "Continuity is missing from $InstancePath"
        }

        $continuityConfig = Join-Path $minecraftPath 'config\continuity.json'
        if (-not (Test-Path -LiteralPath $continuityConfig)) {
            throw "Continuity config is missing from $InstancePath"
        }
        $continuitySettings = Get-Content -LiteralPath $continuityConfig -Raw | ConvertFrom-Json
        if ($continuitySettings.connected_textures -ne $true) {
            throw "connected_textures is not enabled in $continuityConfig"
        }
    }

    $optionsPath = Join-Path $minecraftPath 'options.txt'
    if (-not (Test-Path -LiteralPath $optionsPath)) {
        Copy-Item -LiteralPath $TemplateOptions -Destination $optionsPath
    }

    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($line in [System.IO.File]::ReadAllLines($optionsPath)) {
        $lines.Add($line)
    }
    $packsJson = ConvertTo-Json -Compress -InputObject @($ResourcePacks)
    $resourcePackLine = "resourcePacks:$packsJson"
    $resourcePackIndex = -1
    for ($index = 0; $index -lt $lines.Count; $index++) {
        if ($lines[$index].StartsWith('resourcePacks:')) {
            $resourcePackIndex = $index
            break
        }
    }
    if ($resourcePackIndex -ge 0) {
        $lines[$resourcePackIndex] = $resourcePackLine
    }
    else {
        $lines.Add($resourcePackLine)
    }

    [System.IO.File]::WriteAllLines($optionsPath, $lines, [System.Text.UTF8Encoding]::new($false))
    $provider = if ($RequireContinuity) { 'Continuity' } else { 'vanilla resource-pack content only' }
    Write-Host "Resource packs enabled ($provider): $($InstancePath | Split-Path -Leaf)"
}

function Sync-ResourcePackFiles {
    param(
        [Parameter(Mandatory = $true)][string]$SourceInstance,
        [Parameter(Mandatory = $true)][string]$TargetInstance,
        [Parameter(Mandatory = $true)][string[]]$ResourcePacks,
        [Parameter(Mandatory = $true)][string]$Timestamp
    )

    if ([System.IO.Path]::GetFullPath($SourceInstance) -eq [System.IO.Path]::GetFullPath($TargetInstance)) {
        return
    }

    $sourceRoot = Join-Path $SourceInstance '.minecraft\resourcepacks'
    $targetRoot = Join-Path $TargetInstance '.minecraft\resourcepacks'
    [System.IO.Directory]::CreateDirectory($targetRoot) | Out-Null
    foreach ($resourcePack in $ResourcePacks) {
        if (-not $resourcePack.StartsWith('file/', [System.StringComparison]::Ordinal)) {
            continue
        }
        $fileName = $resourcePack.Substring('file/'.Length)
        if ([System.IO.Path]::GetFileName($fileName) -ne $fileName) {
            throw "Invalid resource-pack filename: $fileName"
        }
        $sourcePath = Join-Path $sourceRoot $fileName
        $targetPath = Join-Path $targetRoot $fileName
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "Template resource pack is missing: $sourcePath"
        }
        $sourceHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash
        if (Test-Path -LiteralPath $targetPath -PathType Leaf) {
            $targetHash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash
            if ($targetHash -ne $sourceHash) {
                $backupRoot = Join-Path $TargetInstance ".voxelbridge-resourcepack-backups\$Timestamp"
                [System.IO.Directory]::CreateDirectory($backupRoot) | Out-Null
                Move-Item -LiteralPath $targetPath -Destination (Join-Path $backupRoot $fileName)
            }
        }
        Copy-Item -LiteralPath $sourcePath -Destination $targetPath -Force
        if ((Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash -ne $sourceHash) {
            throw "Resource-pack hash mismatch after copying to $TargetInstance`: $fileName"
        }
    }
}

if (-not (Test-Path -LiteralPath $Definition)) {
    throw "World definition does not exist: $Definition"
}

$definitionData = [System.IO.File]::ReadAllText($Definition, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
$instancesRoot = Join-Path $PrismRoot 'instances'
$sourceInstancePath = Join-Path $instancesRoot $definitionData.world.sourceInstance
$sourceWorld = Join-Path $sourceInstancePath ".minecraft\saves\$($definitionData.world.folder)"
$sourceLevelDat = Join-Path $sourceWorld 'level.dat'
$sourceOptionsPath = Join-Path $sourceInstancePath '.minecraft\options.txt'
$sourceConfigPath = Join-Path $sourceInstancePath '.minecraft\config\voxelbridge.json'

if (-not (Test-Path -LiteralPath $sourceLevelDat)) {
    throw "Source world is missing level.dat: $sourceWorld"
}

Assert-WorldClosed -WorldPath $sourceWorld
$dataVersion = Get-LevelDataVersion -LevelDat $sourceLevelDat
if ($dataVersion -ne [int]$definitionData.world.expectedDataVersion) {
    throw "Expected DataVersion $($definitionData.world.expectedDataVersion), found $dataVersion in $sourceLevelDat"
}

$sceneEvidence = @()
foreach ($scene in $definitionData.scenes) {
    $sceneEvidence += [ordered]@{
        id = $scene.id
        dimension = $scene.dimension
        pos1 = @($scene.pos1)
        pos2 = @($scene.pos2)
        min = @($scene.min)
        max = @($scene.max)
        regionFiles = @(Get-SceneRegionFiles -WorldPath $sourceWorld -Scene $scene)
    }
}

$levelHash = (Get-FileHash -LiteralPath $sourceLevelDat -Algorithm SHA256).Hash.ToLowerInvariant()
$copiedTargets = @()
$skippedTargets = @()

foreach ($targetId in $definitionData.world.targets) {
    if ($targetId -eq $definitionData.world.sourceInstance) {
        $skippedTargets += "$targetId (source)"
        continue
    }

    $targetInstance = Join-Path $instancesRoot $targetId
    if (-not (Test-Path -LiteralPath $targetInstance)) {
        throw "Target Prism instance does not exist: $targetInstance"
    }

    $targetSaves = Join-Path $targetInstance '.minecraft\saves'
    [System.IO.Directory]::CreateDirectory($targetSaves) | Out-Null
    $targetWorld = Join-Path $targetSaves $definitionData.world.folder
    $replaceExisting = $false

    if (Test-Path -LiteralPath $targetWorld) {
        if (-not $Refresh) {
            $skippedTargets += "$targetId (already exists)"
            continue
        }
        Assert-WorldClosed -WorldPath $targetWorld
        $replaceExisting = $true
    }

    $temporaryWorld = Join-Path $targetSaves "$($definitionData.world.folder).voxelbridge-copy-$([Guid]::NewGuid().ToString('N'))"
    [System.IO.Directory]::CreateDirectory($temporaryWorld) | Out-Null
    Write-Host "Copying world to $targetId ..."
    & robocopy.exe $sourceWorld $temporaryWorld /E /XJ /COPY:DAT /DCOPY:DAT /R:1 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    $copyExitCode = $LASTEXITCODE
    if ($copyExitCode -gt 7) {
        $failedRoot = Join-Path $targetInstance '.voxelbridge-world-copy-failures'
        [System.IO.Directory]::CreateDirectory($failedRoot) | Out-Null
        $failedPath = Join-Path $failedRoot ($temporaryWorld | Split-Path -Leaf)
        Move-Item -LiteralPath $temporaryWorld -Destination $failedPath
        throw "World copy failed for $targetId with robocopy exit code $copyExitCode. Partial copy moved to $failedPath"
    }

    $copyLevelHash = (Get-FileHash -LiteralPath (Join-Path $temporaryWorld 'level.dat') -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($copyLevelHash -ne $levelHash) {
        throw "level.dat hash mismatch after copying to $targetId"
    }

    $marker = [ordered]@{
        schemaVersion = 1
        copiedAtUtc = [DateTime]::UtcNow.ToString('o')
        sourceInstance = $definitionData.world.sourceInstance
        sourceWorld = $definitionData.world.folder
        sourceDataVersion = $dataVersion
        sourceLevelDatSha256 = $levelHash
        scenes = $sceneEvidence
    } | ConvertTo-Json -Depth 10
    [System.IO.File]::WriteAllText(
        (Join-Path $temporaryWorld '.voxelbridge-test-world.json'),
        $marker + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false)
    )
    if ($replaceExisting) {
        Remove-Item -LiteralPath $targetWorld -Recurse -Force
    }
    Move-Item -LiteralPath $temporaryWorld -Destination $targetWorld
    $copiedTargets += $targetId
}

$templateOptions = Join-Path $sourceInstancePath '.minecraft\options.txt'
if (-not (Test-Path -LiteralPath $templateOptions)) {
    throw "The RestWorld template options are missing: $templateOptions"
}
$sourceResourcePacks = @(Get-EnabledResourcePacks -OptionsPath $sourceOptionsPath)
$sourceVoxelBridgeConfig = Get-CleanVoxelBridgeConfig -ConfigPath $sourceConfigPath
Write-Utf8NoBom -Path $sourceConfigPath -Text ($sourceVoxelBridgeConfig + [Environment]::NewLine)
$continuityInstances = @($definitionData.ctm.instances)
foreach ($targetId in $definitionData.world.targets) {
    $targetInstance = Join-Path $instancesRoot $targetId
    $requiresContinuity = $targetId -in $continuityInstances
    $enabledPacks = if ($requiresContinuity) {
        @($sourceResourcePacks)
    } else {
        @($sourceResourcePacks | Where-Object {
            $_ -ne 'fabric' -and -not $_.StartsWith('continuity:', [System.StringComparison]::Ordinal)
        })
    }
    Sync-ResourcePackFiles `
        -SourceInstance $sourceInstancePath `
        -TargetInstance $targetInstance `
        -ResourcePacks @($sourceResourcePacks) `
        -Timestamp $timestamp
    Set-ResourcePacks `
        -InstancePath $targetInstance `
        -ResourcePacks $enabledPacks `
        -TemplateOptions $templateOptions `
        -RequireContinuity $requiresContinuity
    $targetConfig = Join-Path $targetInstance '.minecraft\config\voxelbridge.json'
    Write-Utf8NoBom -Path $targetConfig -Text ($sourceVoxelBridgeConfig + [Environment]::NewLine)
    Write-Host "VoxelBridge config synchronized (vanilla random transform enabled): $targetId"
}

Write-Host ''
Write-Host "Source: $($definitionData.world.sourceInstance) / $($definitionData.world.folder) (DataVersion $dataVersion)"
Write-Host "Copied: $($copiedTargets -join ', ')"
Write-Host "Skipped: $($skippedTargets -join ', ')"
foreach ($scene in $definitionData.scenes) {
    Write-Host "Scene $($scene.id): $($scene.pos1 -join ' ') -> $($scene.pos2 -join ' ')"
}
