[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string[]]$Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Open-GltfDocument {
    param([Parameter(Mandatory = $true)][string]$GltfPath)

    $json = [System.IO.File]::ReadAllText($GltfPath) | ConvertFrom-Json
    $directory = Split-Path -Parent $GltfPath
    $buffers = @()
    foreach ($buffer in $json.buffers) {
        $bufferPath = Join-Path $directory ([string]$buffer.uri)
        $buffers += ,([System.IO.File]::ReadAllBytes($bufferPath))
    }
    return [pscustomobject]@{
        Json = $json
        Buffers = $buffers
    }
}

function Read-FloatAccessor {
    param(
        [Parameter(Mandatory = $true)]$Document,
        [Parameter(Mandatory = $true)][int]$AccessorIndex
    )

    $accessor = $Document.Json.accessors[$AccessorIndex]
    if ([int]$accessor.componentType -ne 5126) {
        throw "Accessor $AccessorIndex is not a FLOAT accessor."
    }
    $componentCount = @{
        SCALAR = 1
        VEC2 = 2
        VEC3 = 3
        VEC4 = 4
    }[[string]$accessor.type]
    if ($null -eq $componentCount) {
        throw "Unsupported accessor type $($accessor.type)."
    }

    $view = $Document.Json.bufferViews[[int]$accessor.bufferView]
    $baseOffset = if ($null -ne $view.PSObject.Properties['byteOffset']) {
        [int]$view.byteOffset
    } else {
        0
    }
    if ($null -ne $accessor.PSObject.Properties['byteOffset']) {
        $baseOffset += [int]$accessor.byteOffset
    }
    $stride = if ($null -ne $view.PSObject.Properties['byteStride']) {
        [int]$view.byteStride
    } else {
        $componentCount * 4
    }

    $bytes = $Document.Buffers[[int]$view.buffer]
    $values = [single[]]::new([int]$accessor.count * $componentCount)
    for ($row = 0; $row -lt [int]$accessor.count; $row++) {
        for ($component = 0; $component -lt $componentCount; $component++) {
            $offset = $baseOffset + $row * $stride + $component * 4
            $values[$row * $componentCount + $component] =
                [System.BitConverter]::ToSingle($bytes, $offset)
        }
    }
    return ,$values
}

function Measure-GltfAtlasPageBindings {
    param([Parameter(Mandatory = $true)][string]$GltfPath)

    $document = Open-GltfDocument -GltfPath $GltfPath
    $json = $document.Json
    $referencedPages = [System.Collections.Generic.HashSet[int]]::new()
    $affected = [System.Collections.Generic.List[object]]::new()
    $totalQuads = 0
    $wrongPageQuads = 0
    $crossPagePrimitives = 0

    for ($meshIndex = 0; $meshIndex -lt $json.meshes.Count; $meshIndex++) {
        $mesh = $json.meshes[$meshIndex]
        foreach ($primitive in @($mesh.primitives)) {
            $material = $json.materials[[int]$primitive.material]
            $baseColorTexture = $material.pbrMetallicRoughness.baseColorTexture
            if ($null -eq $baseColorTexture) {
                continue
            }
            $texture = $json.textures[[int]$baseColorTexture.index]
            $uri = [string]$json.images[[int]$texture.source].uri
            if ($uri -notmatch '(^|/)atlas_(\d+)\.png$') {
                continue
            }

            $boundPage = [int]$Matches[2] - 1001
            $null = $referencedPages.Add($boundPage)
            $uv = Read-FloatAccessor -Document $document `
                -AccessorIndex ([int]$primitive.attributes.TEXCOORD_0)
            if ($uv.Length % 8 -ne 0) {
                throw "TEXCOORD_0 for mesh $($mesh.name) is not quad-aligned."
            }

            $quadCount = [int]($uv.Length / 8)
            $totalQuads += $quadCount
            $primitiveWrong = 0
            $expectedPages = [System.Collections.Generic.HashSet[int]]::new()
            for ($quad = 0; $quad -lt $quadCount; $quad++) {
                $centerU = 0.0
                $centerV = 0.0
                for ($vertex = 0; $vertex -lt 4; $vertex++) {
                    $centerU += [double]$uv[$quad * 8 + $vertex * 2]
                    $centerV += [double]$uv[$quad * 8 + $vertex * 2 + 1]
                }
                $centerU /= 4.0
                $centerV /= 4.0

                # AtlasBuilder encodes pages as UDIM-like integer UV offsets:
                # tileU = page % 10 and tileV = page / 10, with V negated.
                $tileU = [int][System.Math]::Floor($centerU)
                $tileV = -[int][System.Math]::Floor($centerV)
                $expectedPage = $tileV * 10 + $tileU
                $null = $expectedPages.Add($expectedPage)
                if ($expectedPage -ne $boundPage) {
                    $primitiveWrong++
                    $wrongPageQuads++
                }
            }

            if ($expectedPages.Count -gt 1) {
                $crossPagePrimitives++
            }
            if ($primitiveWrong -gt 0) {
                $affected.Add([pscustomobject]@{
                    Mesh = [string]$mesh.name
                    Quads = $quadCount
                    WrongPageQuads = $primitiveWrong
                    BoundPage = $boundPage
                    ExpectedPages = @(($expectedPages | Sort-Object))
                })
            }
        }
    }

    $caseDirectory = Split-Path -Parent (Split-Path -Parent $GltfPath)
    return [pscustomobject]@{
        Case = Split-Path -Leaf $caseDirectory
        Gltf = $GltfPath
        ReferencedAtlasPages = $referencedPages.Count
        Quads = $totalQuads
        WrongPageQuads = $wrongPageQuads
        WrongPercent = if ($totalQuads -gt 0) {
            [System.Math]::Round(100.0 * $wrongPageQuads / $totalQuads, 2)
        } else {
            0.0
        }
        CrossPagePrimitives = $crossPagePrimitives
        AffectedMeshes = $affected.Count
        Affected = @($affected | Sort-Object WrongPageQuads -Descending)
    }
}

$gltfFiles = [System.Collections.Generic.List[string]]::new()
foreach ($candidate in $Path) {
    $resolved = Resolve-Path -LiteralPath $candidate
    if (Test-Path -LiteralPath $resolved.Path -PathType Leaf) {
        $gltfFiles.Add($resolved.Path)
        continue
    }
    foreach ($file in Get-ChildItem -LiteralPath $resolved.Path -Recurse -File -Filter '*.gltf') {
        $gltfFiles.Add($file.FullName)
    }
}

foreach ($gltf in ($gltfFiles | Sort-Object -Unique)) {
    Measure-GltfAtlasPageBindings -GltfPath $gltf
}
