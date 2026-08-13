[CmdletBinding()]
param(
    [string]$LauncherRoot = 'D:\PrismLauncher-Windows-MinGW-w64-Portable-11.0.3',
    [string]$RepositoryRoot = '',
    [string]$InstanceId = 'vb-fabric-1.21.11-showcase',
    [string]$WorldId = 'vb-showcase'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )

    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Text, $encoding)
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

$launcherExe = Join-Path $LauncherRoot 'prismlauncher.exe'
$instanceDir = Join-Path (Join-Path $LauncherRoot 'instances') $InstanceId
$minecraftDir = Join-Path $instanceDir '.minecraft'
$instanceConfigPath = Join-Path $instanceDir 'instance.cfg'
$worldArchive = Join-Path $RepositoryRoot 'golden\worlds\1.21.11.zip'
$sceneFile = Join-Path $RepositoryRoot 'golden\scenarios\showcase\scene.mcfunction'
$worldDir = Join-Path (Join-Path $minecraftDir 'saves') $WorldId
$resultFile = Join-Path $minecraftDir 'golden-results\showcase\result.json'

foreach ($requiredFile in @($launcherExe, $instanceConfigPath, $worldArchive, $sceneFile)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required file was not found: $requiredFile"
    }
}

$runningLauncher = Get-Process -Name 'prismlauncher' -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and ([System.IO.Path]::GetFullPath($_.Path) -eq [System.IO.Path]::GetFullPath($launcherExe)) }
if ($runningLauncher) {
    throw 'Close this Prism Launcher portable installation before preparing the showcase.'
}

if (-not (Test-Path -LiteralPath $worldDir -PathType Container)) {
    New-Item -ItemType Directory -Force -Path $worldDir | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($worldArchive, $worldDir)
}

$baseOptions = Join-Path (Join-Path (Join-Path $LauncherRoot 'instances') 'vb-fabric-1.21.11-restworld') '.minecraft\options.txt'
$showcaseOptions = Join-Path $minecraftDir 'options.txt'
if ((Test-Path -LiteralPath $baseOptions -PathType Leaf) -and -not (Test-Path -LiteralPath $showcaseOptions -PathType Leaf)) {
    Copy-Item -LiteralPath $baseOptions -Destination $showcaseOptions
}

$sceneArgument = $sceneFile.Replace('\', '/')
$resultArgument = $resultFile.Replace('\', '/')
$jvmArguments = @(
    '-Dvoxelbridge.golden.enabled=true',
    "-Dvoxelbridge.golden.scenarioFile=$sceneArgument",
    "-Dvoxelbridge.golden.resultFile=$resultArgument",
    '-Dvoxelbridge.golden.minecraftVersion=1.21.11',
    '-Dvoxelbridge.golden.pos1=0,60,0',
    '-Dvoxelbridge.golden.pos2=47,75,31',
    '-Dvoxelbridge.golden.settleTicks=80',
    '-Dvoxelbridge.golden.exportThreadCount=16',
    '-Dvoxelbridge.golden.atlasMode=atlas',
    '-Dvoxelbridge.golden.autoStop=false',
    '-Dvoxelbridge.golden.timeoutSeconds=600'
) -join ' '

$configLines = New-Object System.Collections.Generic.List[string]
foreach ($line in [System.IO.File]::ReadAllLines($instanceConfigPath)) {
    $configLines.Add($line) | Out-Null
}
Set-CfgValue -Lines $configLines -Key 'OverrideJavaArgs' -Value 'true'
Set-CfgValue -Lines $configLines -Key 'JvmArgs' -Value $jvmArguments
Set-CfgValue -Lines $configLines -Key 'JoinServerOnLaunch' -Value 'false'
Set-CfgValue -Lines $configLines -Key 'JoinWorldOnLaunch' -Value $WorldId
Write-Utf8NoBom -Path $instanceConfigPath -Text (($configLines -join [Environment]::NewLine) + [Environment]::NewLine)

Start-Process -FilePath $launcherExe -ArgumentList @('--launch', $InstanceId, '--world', $WorldId)
