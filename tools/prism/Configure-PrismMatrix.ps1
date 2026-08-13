[CmdletBinding()]
param(
    [string]$LauncherRoot = 'D:\PrismLauncher-Windows-MinGW-w64-Portable-11.0.3',
    [string]$ModrinthProfilesRoot = 'D:\ModrinthApp\profiles',
    [string]$Java17Path = 'C:\Users\29901\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2\bin\javaw.exe',
    [string]$Java21Path = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot\bin\javaw.exe',
    [string]$MatrixPath = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($MatrixPath)) {
    $MatrixPath = Join-Path $PSScriptRoot 'instances.json'
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )

    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Text, $encoding)
}

function Assert-ChildPath {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Path
    )

    $rootPath = [System.IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    $candidatePath = [System.IO.Path]::GetFullPath($Path)
    if (-not $candidatePath.StartsWith($rootPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside the managed instance: $candidatePath"
    }
}

function Get-NormalizedTargetName {
    param([Parameter(Mandatory = $true)][string]$SourceName)

    if ($SourceName.EndsWith('.jar.disabled', [System.StringComparison]::OrdinalIgnoreCase)) {
        return $SourceName.Substring(0, $SourceName.Length - '.disabled'.Length)
    }
    return $SourceName
}

$launcherExe = Join-Path $LauncherRoot 'prismlauncher.exe'
$instancesRoot = Join-Path $LauncherRoot 'instances'
if (-not (Test-Path -LiteralPath $launcherExe -PathType Leaf)) {
    throw "Prism Launcher executable was not found: $launcherExe"
}
if (-not (Test-Path -LiteralPath $MatrixPath -PathType Leaf)) {
    throw "Instance matrix was not found: $MatrixPath"
}

$runningLauncher = Get-Process -Name 'prismlauncher' -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and ([System.IO.Path]::GetFullPath($_.Path) -eq [System.IO.Path]::GetFullPath($launcherExe)) }
if ($runningLauncher) {
    throw 'Close this Prism Launcher portable installation before regenerating managed instances.'
}

$matrixText = [System.IO.File]::ReadAllText($MatrixPath)
$matrix = $matrixText | ConvertFrom-Json
if ($matrix.schemaVersion -ne 1) {
    throw "Unsupported matrix schema: $($matrix.schemaVersion)"
}

New-Item -ItemType Directory -Force -Path $instancesRoot | Out-Null
$matrixHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $MatrixPath).Hash.ToLowerInvariant()
$generatedIds = New-Object System.Collections.Generic.List[string]
$summary = New-Object System.Collections.Generic.List[object]

foreach ($definition in $matrix.instances) {
    if ($definition.id -notmatch '^[a-z0-9][a-z0-9._-]+$') {
        throw "Invalid instance id: $($definition.id)"
    }
    $javaPath = switch ([int]$definition.javaMajor) {
        17 { $Java17Path }
        21 { $Java21Path }
        default { throw "Unsupported Java major for $($definition.id): $($definition.javaMajor)" }
    }
    if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf)) {
        throw "Java executable was not found for $($definition.id): $javaPath"
    }
    $sourceProfilePath = if ([string]$definition.sourceProfile -like '@launcher/*') {
        Join-Path $LauncherRoot ([string]$definition.sourceProfile).Substring('@launcher/'.Length)
    } elseif ([System.IO.Path]::IsPathRooted([string]$definition.sourceProfile)) {
        [string]$definition.sourceProfile
    } else {
        Join-Path $ModrinthProfilesRoot ([string]$definition.sourceProfile)
    }
    if (-not (Test-Path -LiteralPath $sourceProfilePath -PathType Container)) {
        throw "Source profile was not found for $($definition.id): $sourceProfilePath"
    }

    $instanceDir = Join-Path $instancesRoot $definition.id
    $minecraftDir = Join-Path $instanceDir '.minecraft'
    $managedManifestPath = Join-Path $instanceDir '.voxelbridge-managed.json'
    Assert-ChildPath -Root $instancesRoot -Path $instanceDir

    if ((Test-Path -LiteralPath $instanceDir) -and -not (Test-Path -LiteralPath $managedManifestPath -PathType Leaf)) {
        throw "Instance exists but is not managed by this tool: $instanceDir"
    }

    $oldManagedFiles = @()
    if (Test-Path -LiteralPath $managedManifestPath -PathType Leaf) {
        $oldManifest = ([System.IO.File]::ReadAllText($managedManifestPath) | ConvertFrom-Json)
        if ($oldManifest.instanceId -ne $definition.id) {
            $migrationSources = if ($definition.PSObject.Properties.Name -contains 'migrateFrom') {
                @($definition.migrateFrom)
            } else {
                @()
            }
            if ($oldManifest.instanceId -notin $migrationSources) {
                throw "Managed marker does not match instance id: $managedManifestPath"
            }
            Write-Host "Migrating managed instance $($oldManifest.instanceId) -> $($definition.id)"
        }
        $oldManagedFiles = @($oldManifest.managedFiles)
    }

    foreach ($relativePath in $oldManagedFiles) {
        $managedPath = Join-Path $instanceDir $relativePath
        Assert-ChildPath -Root $instanceDir -Path $managedPath
        if (Test-Path -LiteralPath $managedPath -PathType Leaf) {
            Remove-Item -LiteralPath $managedPath -Force
        }
    }

    New-Item -ItemType Directory -Force -Path $minecraftDir | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $minecraftDir 'mods') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $minecraftDir 'config') | Out-Null

    $managedFiles = New-Object System.Collections.Generic.List[string]
    $artifacts = New-Object System.Collections.Generic.List[object]

    foreach ($mod in $definition.mods) {
        $sourcePath = Join-Path $sourceProfilePath $mod.source
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "Locked mod was not found for $($definition.id): $sourcePath"
        }

        $sourceName = Split-Path -Leaf $sourcePath
        $targetName = Get-NormalizedTargetName -SourceName $sourceName
        if ($mod.PSObject.Properties.Name -contains 'target') {
            $targetName = [string]$mod.target
        }
        if ($targetName -notmatch '^[^\\/:*?"<>|]+\.jar$') {
            throw "Invalid target mod filename for $($definition.id): $targetName"
        }

        $destinationPath = Join-Path (Join-Path $minecraftDir 'mods') $targetName
        Copy-Item -LiteralPath $sourcePath -Destination $destinationPath -Force
        $relativeDestination = '.minecraft\mods\' + $targetName
        $managedFiles.Add($relativeDestination) | Out-Null
        $artifacts.Add([ordered]@{
            role = [string]$mod.role
            file = $relativeDestination
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $destinationPath).Hash.ToLowerInvariant()
            source = [string]$sourcePath
        }) | Out-Null
    }

    foreach ($configRelativePath in @($definition.configs)) {
        $sourcePath = Join-Path $sourceProfilePath $configRelativePath
        if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
            throw "Locked config was not found for $($definition.id): $sourcePath"
        }
        $destinationPath = Join-Path $minecraftDir $configRelativePath
        $destinationParent = Split-Path -Parent $destinationPath
        New-Item -ItemType Directory -Force -Path $destinationParent | Out-Null
        Copy-Item -LiteralPath $sourcePath -Destination $destinationPath -Force
        $relativeDestination = '.minecraft\' + $configRelativePath
        $managedFiles.Add($relativeDestination) | Out-Null
        $artifacts.Add([ordered]@{
            role = 'config'
            file = $relativeDestination
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $destinationPath).Hash.ToLowerInvariant()
            source = [string]$sourcePath
        }) | Out-Null
    }

    $javaPathForConfig = $javaPath.Replace('\', '/')
    $instanceConfig = @"
[General]
ConfigVersion=1.2
InstanceType=OneSix
JavaPath=$javaPathForConfig
MaxMemAlloc=$($definition.memoryMiB)
MinMemAlloc=1024
OverrideJavaLocation=true
OverrideMemory=true
UseAccountForInstance=false
iconKey=grass
name=$($definition.name)
notes=Managed VoxelBridge production-like test instance. Target: $($definition.target); variant: $($definition.variant).
"@
    Write-Utf8NoBom -Path (Join-Path $instanceDir 'instance.cfg') -Text ($instanceConfig.Trim() + [Environment]::NewLine)

    $componentManifest = [ordered]@{
        formatVersion = 1
        components = @(
            [ordered]@{
                uid = 'net.minecraft'
                version = [string]$definition.minecraft
                important = $true
            },
            [ordered]@{
                uid = [string]$definition.loader.uid
                version = [string]$definition.loader.version
            }
        )
    }
    Write-Utf8NoBom -Path (Join-Path $instanceDir 'mmc-pack.json') -Text (($componentManifest | ConvertTo-Json -Depth 8) + [Environment]::NewLine)

    $managedManifest = [ordered]@{
        schemaVersion = 1
        instanceId = [string]$definition.id
        target = [string]$definition.target
        variant = [string]$definition.variant
        generatedAtUtc = [DateTime]::UtcNow.ToString('o')
        matrixSha256 = $matrixHash
        managedFiles = $managedFiles.ToArray()
        artifacts = $artifacts.ToArray()
    }
    Write-Utf8NoBom -Path $managedManifestPath -Text (($managedManifest | ConvertTo-Json -Depth 8) + [Environment]::NewLine)

    $generatedIds.Add([string]$definition.id) | Out-Null
    $summary.Add([ordered]@{
        id = [string]$definition.id
        minecraft = [string]$definition.minecraft
        loader = ([string]$definition.loader.uid + '@' + [string]$definition.loader.version)
        mods = @($definition.mods).Count
        configs = @($definition.configs).Count
    }) | Out-Null
}

$groupsPath = Join-Path $instancesRoot 'instgroups.json'
$groups = [ordered]@{}
if (Test-Path -LiteralPath $groupsPath -PathType Leaf) {
    $existingGroups = ([System.IO.File]::ReadAllText($groupsPath) | ConvertFrom-Json)
    if ($existingGroups.PSObject.Properties.Name -contains 'groups') {
        foreach ($property in $existingGroups.groups.PSObject.Properties) {
            $groups[$property.Name] = $property.Value
        }
    }
}
$groups[[string]$matrix.group] = [ordered]@{
    hidden = $false
    instances = $generatedIds.ToArray()
}
$groupManifest = [ordered]@{
    formatVersion = '1'
    groups = $groups
}
Write-Utf8NoBom -Path $groupsPath -Text (($groupManifest | ConvertTo-Json -Depth 8) + [Environment]::NewLine)

$summary.ToArray() | ConvertTo-Json -Depth 5
