[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ShimJar,
    [Parameter(Mandatory = $true)]
    [string[]] $RuntimeClasspathEntries,
    [Parameter(Mandatory = $true)]
    [string] $ReceiptPath,
    [Parameter(Mandatory = $true)]
    [string] $ClasspathScope
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-Sha256File([string] $Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToUpperInvariant()
}

function Get-Sha256Stream([System.IO.Stream] $Stream) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [System.Convert]::ToHexString($sha.ComputeHash($Stream))
    }
    finally {
        $sha.Dispose()
    }
}

function Get-ShimClassHashes([string] $Path) {
    $hashes = [System.Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        foreach ($entry in $archive.Entries) {
            if ($entry.FullName.EndsWith('/') -or -not $entry.FullName.EndsWith('.class', [StringComparison]::Ordinal)) {
                continue
            }
            if ($hashes.ContainsKey($entry.FullName)) {
                throw "Structural shim contains a duplicate class entry: $($entry.FullName)"
            }
            $stream = $entry.Open()
            try {
                $hashes.Add($entry.FullName, (Get-Sha256Stream $stream))
            }
            finally {
                $stream.Dispose()
            }
        }
    }
    finally {
        $archive.Dispose()
    }
    if ($hashes.Count -eq 0) { throw "Structural shim has no class entries: $Path" }
    return ,$hashes
}

function Assert-NoShimClassCopiesInDirectory([string] $Directory, $ShimClassHashes) {
    $checkedOverlaps = 0
    foreach ($classEntry in $ShimClassHashes.Keys) {
        $classPath = Join-Path $Directory ($classEntry.Replace('/', [System.IO.Path]::DirectorySeparatorChar))
        if (-not (Test-Path -LiteralPath $classPath -PathType Leaf)) { continue }
        $checkedOverlaps++
        $actualHash = Get-Sha256File $classPath
        if ($actualHash -eq $ShimClassHashes[$classEntry]) {
            throw "Runtime classpath directory contains a byte-identical structural-shim class: $classPath"
        }
    }
    return $checkedOverlaps
}

function Assert-NoShimClassCopiesInArchive([string] $Path, $ShimClassHashes) {
    $checkedOverlaps = 0
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        foreach ($entry in $archive.Entries) {
            if (-not $ShimClassHashes.ContainsKey($entry.FullName)) { continue }
            $checkedOverlaps++
            $stream = $entry.Open()
            try {
                $actualHash = Get-Sha256Stream $stream
            }
            finally {
                $stream.Dispose()
            }
            if ($actualHash -eq $ShimClassHashes[$entry.FullName]) {
                throw "Runtime classpath archive contains a byte-identical structural-shim class: $Path!$($entry.FullName)"
            }
        }
    }
    finally {
        $archive.Dispose()
    }
    return $checkedOverlaps
}

if (-not (Test-Path -LiteralPath $ShimJar -PathType Leaf)) {
    throw "Structural shim JAR is absent; cannot verify runtime classpath exclusion: $ShimJar"
}
if ($RuntimeClasspathEntries.Count -eq 0) {
    throw 'Runtime classpath exclusion gate requires a non-empty resolved runtime classpath.'
}
if (Test-Path -LiteralPath $ReceiptPath) {
    throw "Refusing to overwrite runtime classpath exclusion receipt: $ReceiptPath"
}

$shimFullPath = [System.IO.Path]::GetFullPath($ShimJar)
$shimHash = Get-Sha256File $shimFullPath
$shimClassHashes = Get-ShimClassHashes $shimFullPath
$observedEntries = [System.Collections.Generic.List[object]]::new()
$checkedOverlappingClassEntries = 0
for ($index = 0; $index -lt $RuntimeClasspathEntries.Count; $index++) {
    $entryPath = [System.IO.Path]::GetFullPath([string]$RuntimeClasspathEntries[$index])
    if ([string]::Equals($entryPath, $shimFullPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Runtime classpath directly includes the non-runtime structural shim at entry[$index]: $entryPath"
    }
    if (Test-Path -LiteralPath $entryPath -PathType Container) {
        $checkedOverlappingClassEntries += Assert-NoShimClassCopiesInDirectory $entryPath $shimClassHashes
        $observedEntries.Add([ordered]@{ index = $index; path = $entryPath; kind = 'DIRECTORY' })
        continue
    }
    if (-not (Test-Path -LiteralPath $entryPath -PathType Leaf)) {
        throw "Runtime classpath entry is absent at entry[$index]: $entryPath"
    }
    $entryHash = Get-Sha256File $entryPath
    if ($entryHash -eq $shimHash) {
        throw "Runtime classpath contains a byte-identical copy of the structural shim at entry[$index]: $entryPath"
    }
    try {
        $checkedOverlappingClassEntries += Assert-NoShimClassCopiesInArchive $entryPath $shimClassHashes
    }
    catch {
        $cause = $_.Exception
        throw [System.IO.InvalidDataException]::new(
                "Runtime classpath structural-shim exclusion failed at entry[$index]: $entryPath", $cause)
    }
    $observedEntries.Add([ordered]@{ index = $index; path = $entryPath; sha256 = $entryHash; kind = 'ARCHIVE' })
}

$receipt = [ordered]@{
    schema = 'accepted-consumer-structural-shim-classpath-exclusion-v1'
    state = 'PASS'
    classpathScope = $ClasspathScope
    shimJarPath = $shimFullPath
    shimJarSha256 = $shimHash
    shimClassEntryCount = $shimClassHashes.Count
    runtimeClasspathEntryCount = $observedEntries.Count
    overlappingShimClassNamesChecked = $checkedOverlappingClassEntries
    runtimeClasspathEntries = $observedEntries
    byteIdenticalShimClassCopies = 0
    result = 'SHIM_PATH_AND_BYTE_IDENTICAL_SHIM_CLASSES_ABSENT_FROM_CHECKED_RUNTIME_ENTRIES'
}
$json = $receipt | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText($ReceiptPath, $json + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false))
$receipt
