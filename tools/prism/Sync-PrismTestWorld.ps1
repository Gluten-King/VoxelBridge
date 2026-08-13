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
        [Parameter(Mandatory = $true)][string]$TemplateOptions
    )

    $minecraftPath = Join-Path $InstancePath '.minecraft'
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
    Write-Host "CTM resource packs enabled: $($InstancePath | Split-Path -Leaf)"
}

if (-not (Test-Path -LiteralPath $Definition)) {
    throw "World definition does not exist: $Definition"
}

$definitionData = Get-Content -LiteralPath $Definition -Raw | ConvertFrom-Json
$instancesRoot = Join-Path $PrismRoot 'instances'
$sourceInstancePath = Join-Path $instancesRoot $definitionData.world.sourceInstance
$sourceWorld = Join-Path $sourceInstancePath ".minecraft\saves\$($definitionData.world.folder)"
$sourceLevelDat = Join-Path $sourceWorld 'level.dat'

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
$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')

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

    if (Test-Path -LiteralPath $targetWorld) {
        if (-not $Refresh) {
            $skippedTargets += "$targetId (already exists)"
            continue
        }
        Assert-WorldClosed -WorldPath $targetWorld
        $backupRoot = Join-Path $targetInstance '.voxelbridge-world-backups'
        [System.IO.Directory]::CreateDirectory($backupRoot) | Out-Null
        $backupPath = Join-Path $backupRoot "$($definitionData.world.folder).$timestamp"
        if (Test-Path -LiteralPath $backupPath) {
            throw "Backup target already exists: $backupPath"
        }
        Move-Item -LiteralPath $targetWorld -Destination $backupPath
        Write-Host "Previous copy backed up: $targetId"
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
    Move-Item -LiteralPath $temporaryWorld -Destination $targetWorld
    $copiedTargets += $targetId
}

$templateOptions = Join-Path (Join-Path $instancesRoot 'vb-fabric-1.21.11-base') '.minecraft\options.txt'
if (-not (Test-Path -LiteralPath $templateOptions)) {
    throw "The 1.21.11 base options template is missing: $templateOptions"
}
foreach ($ctmInstanceId in $definitionData.ctm.instances) {
    $ctmInstance = Join-Path $instancesRoot $ctmInstanceId
    Set-ResourcePacks `
        -InstancePath $ctmInstance `
        -ResourcePacks @($definitionData.ctm.resourcePacks) `
        -TemplateOptions $templateOptions
}

Write-Host ''
Write-Host "Source: $($definitionData.world.sourceInstance) / $($definitionData.world.folder) (DataVersion $dataVersion)"
Write-Host "Copied: $($copiedTargets -join ', ')"
Write-Host "Skipped: $($skippedTargets -join ', ')"
foreach ($scene in $definitionData.scenes) {
    Write-Host "Scene $($scene.id): $($scene.pos1 -join ' ') -> $($scene.pos2 -join ' ')"
}
