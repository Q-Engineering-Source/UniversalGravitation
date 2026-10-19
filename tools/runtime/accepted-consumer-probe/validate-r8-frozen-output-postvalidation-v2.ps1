param(
    [Parameter(Mandatory = $true)]
    [string] $FreezeManifest
)

$ErrorActionPreference = 'Stop'

function Get-Sha256([string] $Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToUpperInvariant()
}

function Get-Sha256Text([string] $Value) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return [System.Convert]::ToHexString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Value)))
    }
    finally { $sha.Dispose() }
}

function Require-Identity([string] $Path, [string] $Sha256, [long] $Length) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Frozen input is absent: $Path" }
    $file = Get-Item -LiteralPath $Path
    $actualSha = Get-Sha256 $Path
    if ($file.Length -ne $Length -or $actualSha -cne $Sha256) {
        throw "Frozen input identity mismatch: $Path expectedLength=$Length actualLength=$($file.Length) expectedSha256=$Sha256 actualSha256=$actualSha"
    }
}

function Assert-FrozenSnapshot([hashtable] $Freeze, [string] $FreezeManifestPath,
        [string] $FreezeManifestSha256, [long] $FreezeManifestLength,
        [string] $StaticStatusPath, [string] $StaticStatusSha256, [long] $StaticStatusLength) {
    Require-Identity $FreezeManifestPath $FreezeManifestSha256 $FreezeManifestLength

    $identityRecords = [System.Collections.Generic.List[object]]::new()
    foreach ($propertyName in @('inputManifestV2','staticFreezeStatusV2','originalInputManifestV1',
            'originalStaticStatusV1','correctionReceipt','inputByteShaVerification','executionReceipt','originalRunnerStatus')) {
        $identity = $Freeze[$propertyName]
        Require-Identity ([string]$identity.path) ([string]$identity.sha256) ([long]$identity.length)
        $identityRecords.Add([ordered]@{ role = $propertyName; path = [string]$identity.path; sha256 = [string]$identity.sha256; length = [long]$identity.length })
    }
    foreach ($source in $Freeze.sourceFiles) {
        Require-Identity ([string]$source.path) ([string]$source.sha256) ([long]$source.length)
        $identityRecords.Add([ordered]@{ role = 'source'; path = [string]$source.path; sha256 = [string]$source.sha256; length = [long]$source.length })
    }
    Require-Identity $StaticStatusPath $StaticStatusSha256 $StaticStatusLength

    $staticStatus = Get-Content -Raw -LiteralPath $StaticStatusPath | ConvertFrom-Json -AsHashtable
    if ($staticStatus.state -cne 'STATIC_FROZEN_AWAITING_OWNER_APPROVAL_POSTVALIDATION_NOT_EXECUTED' -or
            $staticStatus.postvalidationInputManifestSha256 -cne $FreezeManifestSha256 -or
            $staticStatus.postvalidatorSha256 -cne [string]$Freeze.postvalidatorSha256 -or
            $staticStatus.postvalidatorPath -cne [string]$Freeze.postvalidatorPath -or
            $staticStatus.postvalidationExecution -cne 'NOT_EXECUTED' -or
            $staticStatus.newJvm -cne 'NOT_AUTHORIZED_OR_RUN') {
        throw 'Post-validation static status changed or no longer binds this frozen validator/source manifest.'
    }

    $probeManifest = Get-Content -Raw -LiteralPath $Freeze.inputManifestV2.path | ConvertFrom-Json -AsHashtable
    if (@($probeManifest.files).Count -ne 160) { throw "Corrected r8 input manifest must contain exactly 160 frozen files; found=$(@($probeManifest.files).Count)" }
    $inputPaths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($frozenInput in $probeManifest.files) {
        if (-not $inputPaths.Add([string]$frozenInput.path)) { throw "Duplicate corrected input path: $($frozenInput.path)" }
        Require-Identity ([string]$frozenInput.path) ([string]$frozenInput.sha256) ([long]$frozenInput.length)
    }

    $frozenOutputs = @($Freeze.frozenOutputFiles)
    $expectedOutputPaths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $frozenOutputs) {
        if (-not $expectedOutputPaths.Add([string]$entry.path)) { throw "Duplicate output freeze path: $($entry.path)" }
        Require-Identity ([string]$entry.path) ([string]$entry.sha256) ([long]$entry.length)
    }
    $actualOutputPaths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($rootPath in $Freeze.outputRoots) {
        if (-not (Test-Path -LiteralPath $rootPath -PathType Container)) { throw "Frozen output root is absent: $rootPath" }
        foreach ($file in (Get-ChildItem -LiteralPath $rootPath -Recurse -File)) {
            if (-not $actualOutputPaths.Add($file.FullName)) { throw "Duplicate output path under frozen roots: $($file.FullName)" }
        }
    }
    if ($actualOutputPaths.Count -ne $expectedOutputPaths.Count) {
        throw "Frozen output set changed: expected=$($expectedOutputPaths.Count) actual=$($actualOutputPaths.Count)"
    }
    foreach ($actualPath in $actualOutputPaths) {
        if (-not $expectedOutputPaths.Contains($actualPath)) { throw "Unfrozen file appeared under an r8 output root: $actualPath" }
    }
    foreach ($expectedPath in $expectedOutputPaths) {
        if (-not $actualOutputPaths.Contains($expectedPath)) { throw "Frozen r8 output disappeared: $expectedPath" }
    }

    $executionReceipt = Get-Content -Raw -LiteralPath $Freeze.executionReceipt.path | ConvertFrom-Json -AsHashtable
    if ([int]$executionReceipt.execSessionFinalExitCode -ne 1 -or
            $executionReceipt.runnerPath -cne [string]$Freeze.runnerPath -or
            $executionReceipt.runnerSha256 -cne [string]$Freeze.runnerSha256 -or
            $executionReceipt.inputManifestV2Sha256 -cne [string]$Freeze.inputManifestV2.sha256 -or
            $executionReceipt.runnerManifestRoleLabel -cne 'r7-runner' -or
            $executionReceipt.outOfScopePreparationScratch.consumedByRunner -ne $false -or
            $executionReceipt.outOfScopePreparationScratch.consumedByPostvalidator -ne $false) {
        throw 'Frozen execution receipt no longer describes the observed failed r8 run and exact v2 manifest.'
    }
    $runnerStatus = Get-Content -Raw -LiteralPath $Freeze.originalRunnerStatus.path | ConvertFrom-Json -AsHashtable
    if ($runnerStatus.state -cne 'FAILED' -or
            $runnerStatus.revision -cne 'r8-structural-shim-declaration-closure' -or
            $runnerStatus.runnerSha256 -cne [string]$Freeze.runnerSha256 -or
            $runnerStatus.inputManifestPath -cne [string]$Freeze.inputManifestV2.path -or
            $runnerStatus.inputManifestSha256 -cne [string]$Freeze.inputManifestV2.sha256) {
        throw 'Frozen original r8 runner status changed or no longer binds the failed observed invocation.'
    }

    return [ordered]@{
        verifiedInputFileCount = $inputPaths.Count
        verifiedRunnerOutputFileCount = $actualOutputPaths.Count
        verifiedSourceAndEvidenceIdentityCount = $identityRecords.Count + 1
        freezeManifestSha256 = $FreezeManifestSha256
        staticStatusSha256 = $StaticStatusSha256
        inputManifestSha256 = [string]$Freeze.inputManifestV2.sha256
        originalRunnerState = [string]$runnerStatus.state
        originalRunnerExitCode = [int]$executionReceipt.execSessionFinalExitCode
    }
}

function Require-Number([string[]] $Preamble, [string] $Key) {
    $pattern = '^' + [regex]::Escape($Key) + '=(\d+)$'
    $matches = @($Preamble | Where-Object { $_ -cmatch $pattern })
    if ($matches.Count -ne 1) { throw "ABI report must contain exactly one numeric $Key field; found=$($matches.Count)" }
    return [int]([regex]::Match($matches[0], $pattern).Groups[1].Value)
}

function Require-Integer([string] $Value, [string] $Label) {
    $parsed = 0
    if (-not [int]::TryParse($Value, [ref]$parsed)) { throw "Expected integer $Label, got '$Value'" }
    return $parsed
}

function Read-ReportSections([string] $Path, [string[]] $Names) {
    $sections = @{}
    foreach ($name in $Names) {
        $sections[$name] = [ordered]@{
            seen = $false
            header = $null
            rows = [System.Collections.Generic.List[string]]::new()
            waitingForHeader = $false
        }
    }
    $preamble = [System.Collections.Generic.List[string]]::new()
    $reader = [System.IO.StreamReader]::new($Path, [System.Text.Encoding]::UTF8, $true)
    $insideSection = $false
    $current = $null
    try {
        while (($line = $reader.ReadLine()) -ne $null) {
            if ($line.StartsWith('#')) {
                $insideSection = $true
                $name = $line.Substring(1)
                if ($sections.ContainsKey($name)) {
                    if ($sections[$name].seen) { throw "ABI report duplicates section #$name" }
                    $sections[$name].seen = $true
                    $sections[$name].waitingForHeader = $true
                    $current = $name
                }
                else { $current = $null }
                continue
            }
            if (-not $insideSection) {
                $preamble.Add($line)
                continue
            }
            if ($null -eq $current -or [string]::IsNullOrWhiteSpace($line)) { continue }
            if ($sections[$current].waitingForHeader) {
                $sections[$current].header = $line
                $sections[$current].waitingForHeader = $false
            }
            else { $sections[$current].rows.Add($line) }
        }
    }
    finally { $reader.Dispose() }
    foreach ($name in $Names) {
        if (-not $sections[$name].seen -or [string]::IsNullOrWhiteSpace($sections[$name].header)) {
            throw "ABI report section is absent or has no header: #$name"
        }
    }
    return [pscustomobject]@{ preamble = $preamble; sections = $sections }
}

function Write-NewJson([string] $Path, [object] $Value) {
    if (Test-Path -LiteralPath $Path) { throw "Refusing to overwrite post-validation output: $Path" }
    $json = $Value | ConvertTo-Json -Depth 20
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($json + [Environment]::NewLine)
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true) }
    finally { $stream.Dispose() }
}

if (-not (Test-Path -LiteralPath $FreezeManifest -PathType Leaf)) { throw "Post-validation freeze manifest is absent: $FreezeManifest" }
$freeze = Get-Content -Raw -LiteralPath $FreezeManifest | ConvertFrom-Json -AsHashtable
if ($freeze.schema -cne 'accepted-consumer-r8-postvalidation-input-freeze-v2' -or
		$freeze.revision -cne 'r8-frozen-output-postvalidation-v2' -or
        $freeze.scope -cne 'read-only-existing-r8-output-reconciliation-no-jvm-no-launch') {
    throw "Post-validation freeze schema/revision/scope mismatch: $FreezeManifest"
}
if (-not [string]::Equals([System.IO.Path]::GetFullPath([string]$freeze.postvalidatorPath),
        [System.IO.Path]::GetFullPath($PSCommandPath), [StringComparison]::OrdinalIgnoreCase)) {
    throw "Post-validator path differs from frozen identity: expected=$($freeze.postvalidatorPath) actual=$PSCommandPath"
}
Require-Identity $PSCommandPath ([string]$freeze.postvalidatorSha256) ([long]$freeze.postvalidatorLength)
$receiptPath = [string]$freeze.outputReceiptPath
if (Test-Path -LiteralPath $receiptPath) { throw "Refusing to overwrite post-validation receipt: $receiptPath" }

$freezeManifestInitialLength = (Get-Item -LiteralPath $FreezeManifest).Length
$freezeManifestInitialSha256 = Get-Sha256 $FreezeManifest
$staticStatusPath = [string]$freeze.postvalidationStaticStatusPath
$staticStatusInitialLength = (Get-Item -LiteralPath $staticStatusPath).Length
$staticStatusInitialSha256 = Get-Sha256 $staticStatusPath
$snapshotAtStart = Assert-FrozenSnapshot $freeze $FreezeManifest $freezeManifestInitialSha256 $freezeManifestInitialLength `
        $staticStatusPath $staticStatusInitialSha256 $staticStatusInitialLength

Require-Identity ([string]$freeze.inputManifestV2.path) ([string]$freeze.inputManifestV2.sha256) ([long]$freeze.inputManifestV2.length)
Require-Identity ([string]$freeze.staticFreezeStatusV2.path) ([string]$freeze.staticFreezeStatusV2.sha256) ([long]$freeze.staticFreezeStatusV2.length)
Require-Identity ([string]$freeze.originalInputManifestV1.path) ([string]$freeze.originalInputManifestV1.sha256) ([long]$freeze.originalInputManifestV1.length)
Require-Identity ([string]$freeze.originalStaticStatusV1.path) ([string]$freeze.originalStaticStatusV1.sha256) ([long]$freeze.originalStaticStatusV1.length)
Require-Identity ([string]$freeze.correctionReceipt.path) ([string]$freeze.correctionReceipt.sha256) ([long]$freeze.correctionReceipt.length)
Require-Identity ([string]$freeze.inputByteShaVerification.path) ([string]$freeze.inputByteShaVerification.sha256) ([long]$freeze.inputByteShaVerification.length)
Require-Identity ([string]$freeze.executionReceipt.path) ([string]$freeze.executionReceipt.sha256) ([long]$freeze.executionReceipt.length)
Require-Identity ([string]$freeze.originalRunnerStatus.path) ([string]$freeze.originalRunnerStatus.sha256) ([long]$freeze.originalRunnerStatus.length)
foreach ($source in $freeze.sourceFiles) {
    Require-Identity ([string]$source.path) ([string]$source.sha256) ([long]$source.length)
}

$staticStatus = Get-Content -Raw -LiteralPath $freeze.postvalidationStaticStatusPath | ConvertFrom-Json -AsHashtable
if ($staticStatus.state -cne 'STATIC_FROZEN_AWAITING_OWNER_APPROVAL_POSTVALIDATION_NOT_EXECUTED' -or
        $staticStatus.postvalidationInputManifestSha256 -cne (Get-Sha256 $FreezeManifest) -or
        $staticStatus.postvalidatorSha256 -cne (Get-Sha256 $PSCommandPath)) {
    throw 'Post-validation static status does not bind this validator and frozen inputs.'
}

$probeManifest = Get-Content -Raw -LiteralPath $freeze.inputManifestV2.path | ConvertFrom-Json -AsHashtable
if ($probeManifest.revision -cne 'r8-structural-shim-declaration-closure' -or
        $probeManifest.scope -cne 'compile-remap-bytecode-preflight-only' -or
        @($probeManifest.files).Count -ne 160) {
    throw 'Corrected r8 input manifest has an unexpected revision, scope, or input count.'
}
$inputPaths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($frozenInput in $probeManifest.files) {
    if (-not $inputPaths.Add([string]$frozenInput.path)) { throw "Duplicate corrected input path: $($frozenInput.path)" }
    Require-Identity ([string]$frozenInput.path) ([string]$frozenInput.sha256) ([long]$frozenInput.length)
}
$executionReceipt = Get-Content -Raw -LiteralPath $freeze.executionReceipt.path | ConvertFrom-Json -AsHashtable
if ([int]$executionReceipt.execSessionFinalExitCode -ne 1 -or
        $executionReceipt.runnerPath -cne $freeze.runnerPath -or
        $executionReceipt.runnerSha256 -cne $freeze.runnerSha256 -or
        $executionReceipt.inputManifestV2Sha256 -cne $freeze.inputManifestV2.sha256 -or
        $executionReceipt.runnerManifestRoleLabel -cne 'r7-runner' -or
        $executionReceipt.outOfScopePreparationScratch.consumedByRunner -ne $false -or
        $executionReceipt.outOfScopePreparationScratch.consumedByPostvalidator -ne $false) {
    throw 'Execution receipt does not preserve the actual r8 runner invocation and historical role metadata.'
}

$runnerStatus = Get-Content -Raw -LiteralPath $freeze.originalRunnerStatus.path | ConvertFrom-Json -AsHashtable
if ($runnerStatus.state -cne 'FAILED' -or $runnerStatus.revision -cne 'r8-structural-shim-declaration-closure' -or
        $runnerStatus.runnerSha256 -cne $freeze.runnerSha256 -or
        $runnerStatus.inputManifestPath -cne $freeze.inputManifestV2.path -or
        $runnerStatus.inputManifestSha256 -cne $freeze.inputManifestV2.sha256 -or
        $runnerStatus.failure.message -cne $freeze.expectedOriginalRunnerFailure -or
        $runnerStatus.failure.scriptStackTrace -notmatch 'build-probe-r8-structural-shim-declaration-closure\.ps1: line 422') {
    throw 'Original r8 runner failure status does not match the frozen observed failure.'
}
if ($runnerStatus.launcher -cne 'NOT_EXECUTED' -or $runnerStatus.game -cne 'NOT_EXECUTED' -or
        $runnerStatus.dedicatedServer -cne 'NOT_EXECUTED') {
    throw 'Original r8 status unexpectedly claims a launcher, game, or dedicated-server execution.'
}
$expectedPhases = @($freeze.expectedPhases)
if (@($runnerStatus.phases).Count -ne $expectedPhases.Count) { throw 'Runner phase count differs from the frozen expected phase list.' }
for ($i = 0; $i -lt $expectedPhases.Count; $i++) {
    $phase = $runnerStatus.phases[$i]
    if ($phase.phase -cne $expectedPhases[$i] -or $phase.state -cne 'PASS' -or [int]$phase.exitCode -ne 0) {
        throw "Observed runner phase is not PASS at index $i phase=$($phase.phase) state=$($phase.state) exit=$($phase.exitCode)"
    }
    foreach ($log in @(@{ path = [string]$phase.stdoutPath; length = [long]$phase.stdoutOriginalBytes },
            @{ path = [string]$phase.stderrPath; length = [long]$phase.stderrOriginalBytes })) {
        if (-not [string]::IsNullOrWhiteSpace($log.path)) {
            $file = Get-Item -LiteralPath $log.path
            if ($file.Length -ne $log.length) { throw "Phase log byte count mismatch: $($log.path)" }
        }
    }
}

$actualOutputFiles = [System.Collections.Generic.List[string]]::new()
foreach ($rootPath in $freeze.outputRoots) {
    if (-not (Test-Path -LiteralPath $rootPath -PathType Container)) { throw "Frozen output root is absent: $rootPath" }
    foreach ($file in (Get-ChildItem -LiteralPath $rootPath -Recurse -File)) { $actualOutputFiles.Add($file.FullName) }
}
$frozenOutputs = @($freeze.frozenOutputFiles)
if ($actualOutputFiles.Count -ne $frozenOutputs.Count) {
    throw "Frozen output file count changed: expected=$($frozenOutputs.Count) actual=$($actualOutputFiles.Count)"
}
$outputPathSet = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($entry in $frozenOutputs) {
    if (-not $outputPathSet.Add([string]$entry.path)) { throw "Duplicate output freeze path: $($entry.path)" }
    Require-Identity ([string]$entry.path) ([string]$entry.sha256) ([long]$entry.length)
}
foreach ($actualPath in $actualOutputFiles) {
    if (-not $outputPathSet.Contains($actualPath)) { throw "Unfrozen file appeared under an r8 output root: $actualPath" }
}

$evidenceRoot = [string]$freeze.evidenceRoot
$buildRoot = [string]$freeze.buildRoot
$reportPath = [string]$freeze.abiReportPath
$report = Read-ReportSections $reportPath @('classes','members','verifierShimClassMappings',
        'verifierShimClassReferenceEdges','verifierShimStructuralMemberDecisions',
        'verifierShimStructureClasses','verifierShimStructureMembers')
$preamble = @($report.preamble)
$sections = $report.sections
$classRows = $sections['verifierShimStructureClasses'].rows
$structureMemberRows = $sections['verifierShimStructureMembers'].rows
$strictClassRows = $sections['classes'].rows
$strictMemberRows = $sections['members'].rows
$shimClassMappingRows = $sections['verifierShimClassMappings'].rows
$shimClassReferenceEdgeRows = $sections['verifierShimClassReferenceEdges'].rows
$shimMemberDecisionRows = $sections['verifierShimStructuralMemberDecisions'].rows

$classCount = Require-Number $preamble 'verifierShimStructureClassCount'
$fieldCount = Require-Number $preamble 'verifierShimStructureFieldCount'
$methodCount = Require-Number $preamble 'verifierShimStructureMethodCount'
$throwStubCount = Require-Number $preamble 'verifierShimThrowStubCount'
$abstractNativeNoCodeCount = Require-Number $preamble 'verifierShimAbstractNativeNoCodeCount'
$uniqueStrictReferenceCount = Require-Number $preamble 'uniqueMinecraftReferences'
$shimClassMappingCount = Require-Number $preamble 'verifierShimClassMappingCount'
$shimExactClassCount = Require-Number $preamble 'verifierShimMappedExactClassCount'
$shimPreservedClassCount = Require-Number $preamble 'verifierShimPreservedActualDeclarationClassCount'
$shimClassReferenceEdgeCount = Require-Number $preamble 'verifierShimStructuralClassReferenceEdgeCount'
$shimMemberDecisionCount = Require-Number $preamble 'verifierShimStructuralMemberDecisionCount'
$policy = 'verifierShimMemberResolutionPolicy=EXACT_MCP_SRG_MD_OR_FD_WHEN_PRESENT;OTHER_DIRECT_ACTUAL_DECLARATIONS_PRESERVE_SOURCE_NAME;SIBLING_DESCRIPTOR_ROWS_ARE_SEPARATE_MEMBERS'
if (@($preamble | Where-Object { $_ -ceq $policy }).Count -ne 1) { throw 'ABI report lacks the exact structural member mapping policy.' }
if (@($preamble | Where-Object { $_ -ceq 'state=PASS' }).Count -ne 1 -or
        @($preamble | Where-Object { $_ -ceq 'scope=BYTECODE_ABI_PREFLIGHT_ONLY' }).Count -ne 1 -or
        @($preamble | Where-Object { $_ -ceq 'verifierShimState=GENERATED_NOT_RUNTIME_ORACLE' }).Count -ne 1 -or
        @($preamble | Where-Object { $_ -ceq 'verifierShimStructureState=PASS' }).Count -ne 1 -or
        @($preamble | Where-Object { $_ -ceq 'verifierShimStructureComparison=REMAPPED_MCP_API_CLASSNODE_VS_GENERATED_SHIM_CLASSNODE' }).Count -ne 1) {
    throw 'ABI report is not in the expected structural shim and strict-preflight state.'
}
if ($classRows.Count -ne $classCount -or $structureMemberRows.Count -ne ($fieldCount + $methodCount) -or
        $strictMemberRows.Count -ne $uniqueStrictReferenceCount -or $uniqueStrictReferenceCount -lt 1 -or
        ($throwStubCount + $abstractNativeNoCodeCount) -ne $methodCount) {
    throw "ABI report structure counts do not reconcile: classes=$classCount/$($classRows.Count) fields=$fieldCount methods=$methodCount memberRows=$($structureMemberRows.Count) throw=$throwStubCount abstractNative=$abstractNativeNoCodeCount strictRefs=$uniqueStrictReferenceCount/$($strictMemberRows.Count)"
}
if ($shimClassMappingCount -lt 1 -or $shimClassMappingRows.Count -ne $shimClassMappingCount -or
        ($shimExactClassCount + $shimPreservedClassCount) -ne $shimClassMappingCount -or
        $shimClassMappingCount -ne $classCount -or
        $shimClassReferenceEdgeRows.Count -ne $shimClassReferenceEdgeCount -or
        $shimMemberDecisionRows.Count -ne $shimMemberDecisionCount) {
    throw "Structural-shim closure counts do not reconcile after correct section parsing: classes=$shimClassMappingCount/$($shimClassMappingRows.Count)/$classCount exact=$shimExactClassCount preserved=$shimPreservedClassCount edges=$shimClassReferenceEdgeCount/$($shimClassReferenceEdgeRows.Count) members=$shimMemberDecisionCount/$($shimMemberDecisionRows.Count)"
}

$shimSourceClasses = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$shimTargetClasses = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($row in $shimClassMappingRows) {
    $columns = $row.Split([char]9)
    if ($columns.Count -ne 10 -or -not $columns[0].StartsWith('net/minecraft/') -or
            [string]::IsNullOrWhiteSpace($columns[1]) -or $columns[2] -notin @('EXACT_MCP_SRG_CL_MAPPING','PRESERVED_ACTUAL_DECLARATION_WITHOUT_CL_MAPPING') -or
            $columns[3] -ne 'YES' -or [string]::IsNullOrWhiteSpace($columns[4]) -or
            [string]::IsNullOrWhiteSpace($columns[9]) -or
            -not $shimSourceClasses.Add($columns[0]) -or -not $shimTargetClasses.Add($columns[1])) {
        throw "Structural-shim class decision row is incomplete or target-colliding: $row"
    }
    if ($columns[2] -eq 'PRESERVED_ACTUAL_DECLARATION_WITHOUT_CL_MAPPING' -and
            ($columns[0] -cne $columns[1] -or $columns[7] -cne $columns[8])) {
        throw "Unmapped structural declaration did not preserve its actual package: $row"
    }
}
$edgeKeys = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$fixedIdentityTypes = @('net/minecraft/launchwrapper/Launch','net/minecraft/launchwrapper/LaunchClassLoader')
foreach ($row in $shimClassReferenceEdgeRows) {
    $columns = $row.Split([char]9)
    if ($columns.Count -ne 4 -or -not $shimSourceClasses.Contains($columns[0]) -or
            -not $columns[1].StartsWith('net/minecraft/') -or
            $columns[2] -notin @('FIXED_LAUNCHWRAPPER_IDENTITY','EXACT_MCP_SRG_CL_MAPPING','PRESERVED_ACTUAL_DECLARATION_WITHOUT_CL_MAPPING') -or
            $columns[3] -ne 'ASM_CLASS_REMAPPER_SKIP_CODE_STANDARD_METADATA' -or
            -not $edgeKeys.Add($columns[0] + [char]0 + $columns[1])) {
        throw "Structural-shim metadata closure edge is invalid or duplicated: $row"
    }
    if ($columns[2] -ne 'FIXED_LAUNCHWRAPPER_IDENTITY' -and -not $shimSourceClasses.Contains($columns[1])) {
        throw "Structural-shim metadata edge points outside the actual declaration closure: $row"
    }
    if ($columns[2] -eq 'FIXED_LAUNCHWRAPPER_IDENTITY' -and $fixedIdentityTypes -cnotcontains $columns[1]) {
        throw "Structural-shim metadata edge uses an unapproved LaunchWrapper identity class: $row"
    }
}
$decisionKeys = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($row in $shimMemberDecisionRows) {
    $columns = $row.Split([char]9)
    if ($columns.Count -ne 10 -or $columns[0] -notin @('FIELD','METHOD','ENCLOSING_CLASS') -or
            [string]::IsNullOrWhiteSpace($columns[1]) -or [string]::IsNullOrWhiteSpace($columns[5]) -or
            [string]::IsNullOrWhiteSpace($columns[8]) -or [string]::IsNullOrWhiteSpace($columns[9]) -or
            -not $decisionKeys.Add(($columns[0..3] -join [char]0))) {
        throw "Structural-shim member decision row is incomplete or duplicated: $row"
    }
    [void](Require-Integer $columns[4] 'structural member declaration access')
    if ($columns[0] -eq 'ENCLOSING_CLASS') {
        if (-not [string]::IsNullOrEmpty($columns[2]) -or -not [string]::IsNullOrEmpty($columns[3]) -or
                -not [string]::IsNullOrEmpty($columns[7])) {
            throw "Enclosing-class metadata row unexpectedly carries a method declaration: $row"
        }
    }
    elseif ([string]::IsNullOrWhiteSpace($columns[2]) -or [string]::IsNullOrWhiteSpace($columns[3]) -or
            [string]::IsNullOrWhiteSpace($columns[7])) {
        throw "Structural-shim field/method decision lacks its exact declaration descriptor: $row"
    }
}

if ($strictClassRows.Count -ne 6 -or $strictMemberRows.Count -ne 9) { throw 'Strict Notch oracle report must contain the observed 6 classes and 9 references.' }
foreach ($row in $strictClassRows) {
    $columns = $row.Split([char]9)
    if ($columns.Count -ne 7 -or -not $columns[0].StartsWith('net/minecraft/') -or
            [string]::IsNullOrWhiteSpace($columns[3]) -or [string]::IsNullOrWhiteSpace($columns[4])) {
        throw "Strict class oracle row is incomplete: $row"
    }
    [void](Require-Integer $columns[1] 'strict MCP class access')
}
$classNames = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$summedFieldCount = 0
$summedMethodCount = 0
$summedThrowStubCount = 0
$summedAbstractNativeNoCodeCount = 0
foreach ($row in $classRows) {
    $columns = $row.Split([char]9)
    if ($columns.Count -ne 12 -or -not $columns[0].StartsWith('net/minecraft/') -or
            -not $classNames.Add($columns[1]) -or [int]$columns[6] -ne ([int]$columns[7] + [int]$columns[8]) -or
            $columns[9] -notmatch '^[A-F0-9]{64}$' -or $columns[10] -notmatch '^[A-F0-9]{64}$' -or
            $columns[9] -cne $columns[10] -or $columns[11] -ne 'PASS') {
        throw "Structural verifier-shim class row failed metadata parity: $row"
    }
    [void](Require-Integer $columns[2] 'structural class access')
    $summedFieldCount += [int]$columns[5]
    $summedMethodCount += [int]$columns[6]
    $summedThrowStubCount += [int]$columns[7]
    $summedAbstractNativeNoCodeCount += [int]$columns[8]
}
if ($summedFieldCount -ne $fieldCount -or $summedMethodCount -ne $methodCount -or
        $summedThrowStubCount -ne $throwStubCount -or $summedAbstractNativeNoCodeCount -ne $abstractNativeNoCodeCount) {
    throw 'Structural verifier-shim class rows do not reconcile to summary counts.'
}
foreach ($row in $structureMemberRows) {
    $columns = $row.Split([char]9)
    if ($columns.Count -ne 10 -or -not $classNames.Contains($columns[0]) -or
            @('FIELD','METHOD') -notcontains $columns[1] -or
            [string]::IsNullOrWhiteSpace($columns[2]) -or [string]::IsNullOrWhiteSpace($columns[4]) -or
            $columns[7] -notmatch '^[A-F0-9]{64}$' -or $columns[8] -notmatch '^[A-F0-9]{64}$' -or
            $columns[7] -cne $columns[8] -or $columns[9] -ne 'PASS') {
        throw "Structural verifier-shim member row failed declaration/access/descriptor parity: $row"
    }
    [void](Require-Integer $columns[3] 'structural member access')
}
foreach ($row in $strictMemberRows) {
    $columns = $row.Split([char]9)
    if ($columns.Count -ne 24 -or @('FIELD','METHOD') -notcontains $columns[0] -or
            @($columns[1..18] | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -gt 0) {
        throw "Strict MCP-SRG-Notch/original-client per-reference row is incomplete: $row"
    }
}

$memberReportSha = Get-Sha256 $reportPath
$abiLogPath = Join-Path $evidenceRoot '06-mcp-srg-notch-abi-remap.stdout.log'
$abiLog = (Get-Content -Raw -LiteralPath $abiLogPath).Trim()
$probeJar = Join-Path $buildRoot 'accepted-consumer-probe-srg-r8-structural-shim-declaration-closure.jar'
$shimJar = Join-Path $buildRoot 'srg-api-verifier-shim-r8-structural-shim-declaration-closure.jar'
$probeJarSha = Get-Sha256 $probeJar
$shimJarSha = Get-Sha256 $shimJar
if ($abiLog -notmatch '^MCP_SRG_NOTCH_ORIGINAL_CLIENT_ABI_PASS members=9 classes=6 probeSha256=([A-F0-9]{64}) verifierShimSha256=([A-F0-9]{64}) originalClientSha256=([A-F0-9]{64})$' -or
        $Matches[1] -cne $probeJarSha -or $Matches[2] -cne $shimJarSha -or
        $Matches[3] -cne [string]$freeze.originalClientSha256) {
    throw "Strict MCP-SRG-Notch remap summary does not match frozen outputs: $abiLog"
}

$cpPath = Join-Path $evidenceRoot 'runtime-classpath-exclusion.json'
$cp = Get-Content -Raw -LiteralPath $cpPath | ConvertFrom-Json -AsHashtable
if ($cp.state -cne 'PASS' -or $cp.classpathScope -cne 'PREPARED_FIXED_GAME_MOD_PROFILE_135_ENTRY_SET_NOT_LAUNCHED' -or
        $cp.shimJarPath -cne $shimJar -or $cp.shimJarSha256 -cne $shimJarSha -or
        [int]$cp.shimClassEntryCount -ne $classCount -or [int]$cp.runtimeClasspathEntryCount -ne 138 -or
        @($cp.runtimeClasspathEntries).Count -ne 138 -or [int]$cp.overlappingShimClassNamesChecked -ne 30 -or
        [int]$cp.byteIdenticalShimClassCopies -ne 0 -or
        $cp.result -cne 'SHIM_PATH_AND_BYTE_IDENTICAL_SHIM_CLASSES_ABSENT_FROM_CHECKED_RUNTIME_ENTRIES') {
    throw 'Structural-shim prepared-classpath exclusion receipt does not match expected finite gate.'
}
$runtimePaths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($entry in $cp.runtimeClasspathEntries) {
    if (-not $runtimePaths.Add([string]$entry.path) -or
            [string]::Equals([System.IO.Path]::GetFullPath([string]$entry.path), [System.IO.Path]::GetFullPath($shimJar), [StringComparison]::OrdinalIgnoreCase)) {
        throw "Duplicate runtime classpath entry or verifier shim leak: $($entry.path)"
    }
    $frozen = @($probeManifest.files | Where-Object { [string]::Equals([string]$_.path, [string]$entry.path, [StringComparison]::OrdinalIgnoreCase) })
    if ($frozen.Count -ne 1 -or $frozen[0].sha256 -cne $entry.sha256) {
        throw "Prepared runtime entry is not the exact frozen input identity: $($entry.path)"
    }
}
$expectedRuntimeEntries = @(
    [string]$probeManifest.pinnedCandidateIdentity.path,
    [string]$probeManifest.uieIdentity.path,
    [string]$probeManifest.chbIdentity.path
) + @($probeManifest.profile.runtimeEntries | ForEach-Object { [string]$_.path })
if ([int]$probeManifest.profile.classpathCount -ne 135 -or $expectedRuntimeEntries.Count -ne 138) {
    throw 'Frozen profile/mod runtime-entry identity does not expand to the expected 138-entry prepared set.'
}
$expectedRuntimeSet = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($expectedPath in $expectedRuntimeEntries) { [void]$expectedRuntimeSet.Add([System.IO.Path]::GetFullPath($expectedPath)) }
if ($expectedRuntimeSet.Count -ne 138 -or
        @($runtimePaths | Where-Object { -not $expectedRuntimeSet.Contains([System.IO.Path]::GetFullPath($_)) }).Count -ne 0 -or
        @($expectedRuntimeEntries | Where-Object { -not $runtimePaths.Contains($_) }).Count -ne 0) {
    throw 'Prepared runtime classpath does not exactly equal the frozen three mod jars plus 135 fixed profile entries.'
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($shimJar)
try {
    $archiveClassCount = @($zip.Entries | Where-Object {
        $_.FullName.StartsWith('net/minecraft/', [StringComparison]::Ordinal) -and $_.FullName.EndsWith('.class', [StringComparison]::Ordinal)
    }).Count
}
finally { $zip.Dispose() }
if ($archiveClassCount -ne [int]$cp.shimClassEntryCount) { throw "Verifier shim archive class count mismatch: $archiveClassCount" }

$preflightSource = [string]$freeze.preflightSourcePath
$preflightText = Get-Content -Raw -LiteralPath $preflightSource
if ($preflightText -notmatch 'Class\.forName\(name,\s*false,\s*loader\)') { throw 'Frozen preflight source does not use non-initializing Class.forName.' }
$preflightPath = Join-Path $evidenceRoot '07-java-bytecode-preflight.stdout.log'
$preflightLines = @(Get-Content -LiteralPath $preflightPath)
$expectedTargets = @($probeManifest.preflightTargets)
if ($preflightLines.Count -ne $expectedTargets.Count -or $expectedTargets.Count -ne 7) { throw 'Finite preflight output does not contain exactly the seven frozen target results.' }
$loadedTargets = [System.Collections.Generic.List[string]]::new()
for ($i = 0; $i -lt $preflightLines.Count; $i++) {
    if ($preflightLines[$i] -notmatch '^PREFLIGHT_LOADED (?<name>\S+) loader=(?<loader>.+)$' -or
            $Matches['name'] -cne [string]$expectedTargets[$i] -or [string]::IsNullOrWhiteSpace($Matches['loader'])) {
        throw "Finite preflight output differs from frozen target index $i : $($preflightLines[$i])"
    }
    $loadedTargets.Add($Matches['name'])
}

$output = [ordered]@{
    schema = 'accepted-consumer-r8-frozen-output-postvalidation-receipt-v2'
	revision = 'r8-frozen-output-postvalidation-v2'
    state = 'POST_VALIDATION_PASS_EXISTING_EVIDENCE_ONLY'
    scope = 'read-only-existing-r8-output-reconciliation-no-jvm-no-launch'
    evidenceTiming = 'TERMINAL_POSTRUN_READ_ONLY_RECHECK_NOT_PART_OF_ORIGINAL_RUNNER'
    validatedUtc = [DateTime]::UtcNow.ToString('o')
    inputFreezeManifestPath = [System.IO.Path]::GetFullPath($FreezeManifest)
    inputFreezeManifestSha256 = Get-Sha256 $FreezeManifest
    postvalidatorPath = $PSCommandPath
    postvalidatorSha256 = Get-Sha256 $PSCommandPath
    originalRunnerState = 'FAILED'
    originalRunnerExitCodeObserved = 1
    originalRunnerFailure = $runnerStatus.failure
    runnerFailureExplanation = 'The original runner reached all ten PASS phases, then its PowerShell section reader returned each row collection as a nested single element. The actual TSV rows and summary counts reconcile when parsed as lines; this post-validation does not rewrite the original runner status.'
    runnerRoleHistory = [ordered]@{
        manifestRoleLabel = [string]$freeze.runnerManifestRoleLabel
        labelIsHistorical = $true
        actualRunnerPath = [string]$freeze.runnerPath
        actualRunnerSha256 = [string]$freeze.runnerSha256
    }
    checks = [ordered]@{
        frozen160InputsRecheckedBeforeAndAfter = 160
        frozenInputMissing = 0
        frozenInputShaOrLengthMismatch = 0
        outputFilesRecheckedBeforeAndAfter = $frozenOutputs.Count
        outputFileMissingExtraOrMismatch = 0
        runnerPhases = @($runnerStatus.phases | ForEach-Object { [ordered]@{ phase = $_.phase; exitCode = $_.exitCode; state = $_.state } })
        correctedReportSectionRowCounts = [ordered]@{
            strictClasses = $strictClassRows.Count
            strictReferences = $strictMemberRows.Count
            shimClassMappings = $shimClassMappingRows.Count
            shimMetadataEdges = $shimClassReferenceEdgeRows.Count
            shimMemberDecisions = $shimMemberDecisionRows.Count
            shimStructureClasses = $classRows.Count
            shimStructureMembers = $structureMemberRows.Count
        }
        strictReferenceOracle = [ordered]@{
            state = 'PASS_IN_ORIGINAL_PHASE_06'
            memberRows = $strictMemberRows.Count
            classRows = $strictClassRows.Count
            originalClientSha256 = [string]$freeze.originalClientSha256
            reportSha256 = $memberReportSha
        }
        shimStructure = [ordered]@{
            state = 'PASS_IN_REPORT_AND_POSTVALIDATION'
            oracle = 'GENERATED_NOT_RUNTIME_ORACLE'
            classCount = $classCount
            exactMappedClassCount = $shimExactClassCount
            preservedActualDeclarationClassCount = $shimPreservedClassCount
            classReferenceEdgeCount = $shimClassReferenceEdgeRows.Count
            structuralMemberDecisionCount = $shimMemberDecisionRows.Count
            fieldDeclarationCount = $fieldCount
            methodDeclarationCount = $methodCount
            throwStubMethodCount = $throwStubCount
            abstractNativeNoCodeMethodCount = $abstractNativeNoCodeCount
            unknownAttributes = 'OUTSIDE_STANDARD_CLASSNODE_COMPARISON;NO_PRESERVATION_CLAIM;METHOD_CUSTOM_ATTRIBUTES_DROPPED'
        }
        preparedClasspathExclusion = [ordered]@{
            state = $cp.state
            scope = $cp.classpathScope
            runtimeEntryCount = $cp.runtimeClasspathEntryCount
            shimClassEntryCount = $archiveClassCount
            overlappingClassNamesChecked = $cp.overlappingShimClassNamesChecked
            byteIdenticalShimClassCopies = $cp.byteIdenticalShimClassCopies
            result = $cp.result
        }
        bytecodePreflight = [ordered]@{
            state = 'PASS_IN_ORIGINAL_PHASE_07'
            mode = 'JAVA_XVERIFY_ALL_CLASS_FOR_NAME_INITIALIZE_FALSE'
            loadedTargets = @($loadedTargets.ToArray())
            count = $loadedTargets.Count
        }
    }
    limitations = @(
        'Original r8 runner exit remains FAILED/1; this independent post-validation does not alter that evidence.',
        'Frozen input and output identities are rechecked at post-validator entry and again after reconciliation; these checks occur after the original runner terminated.',
        'The verifier shim is structural-only and was excluded from the prepared 138-entry classpath; it is not a runtime oracle.',
        'Only seven target classes were loaded with verification and initialize=false; no lazy native calls or runtime interactions were exercised.',
        'Launcher, game client, and dedicated server were NOT_EXECUTED.'
    )
    outOfScopePreparationScratch = $freeze.outOfScopePreparationScratch
    launcher = 'NOT_EXECUTED'
    game = 'NOT_EXECUTED'
    dedicatedServer = 'NOT_EXECUTED'
    gradle = 'NOT_EXECUTED'
}
 $snapshotAtEnd = Assert-FrozenSnapshot $freeze $FreezeManifest $freezeManifestInitialSha256 $freezeManifestInitialLength `
        $staticStatusPath $staticStatusInitialSha256 $staticStatusInitialLength
 $output.checks.snapshotIdentity = [ordered]@{ beforeValidation = $snapshotAtStart; afterValidation = $snapshotAtEnd }
Write-NewJson $receiptPath $output
Write-Output "R8_FROZEN_OUTPUT_POSTVALIDATION_V2_PASS originalRunner=FAILED/1 receipt=$receiptPath receiptSha256=$(Get-Sha256 $receiptPath)"
