param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('r5-verifier-shim-field-owner-retest')]
    [string] $BuildRevision,
    [Parameter(Mandatory = $true)]
    [string] $InputManifest
)

$ErrorActionPreference = 'Stop'
$repoRoot = 'D:\WI - Dev Workspace\UniversalGravitation-src\UniversalGravitation'
$toolRoot = Join-Path $repoRoot 'tools\runtime\accepted-consumer-probe'
$artifactRoot = 'C:\GradleCaches\artifacts\UniversalGravitation-accepted-consumers-native-20261010'
$buildRoot = Join-Path $artifactRoot 'probe-build-r5-verifier-shim-field-owner-retest'
$evidenceRoot = Join-Path $artifactRoot 'evidence\probe-bytecode-preflight-r5-verifier-shim-field-owner-retest'
$statusPath = Join-Path $evidenceRoot 'probe-r5-verifier-shim-field-owner-retest-status.json'
$handoffPath = Join-Path $evidenceRoot 'probe-r5-verifier-shim-field-owner-retest-handoff-manifest.json'
$pinnedCandidate = 'C:\GradleCaches\artifacts\UniversalGravitation-native-checkpoint-20261010-r1\candidate-unigrav.jar'
$pinnedUie = 'C:\GradleCaches\artifacts\NeoFontRender-UIE085-g-pole-20261009\final-followup-r1\neofontrender-ui-enhancements-0.8.5-dev.jar'
$pinnedChb = 'C:\GradleCaches\artifacts\UG-CHB-CAPSULE-V1\32AAFB73C74482DAD4EF80A8A5F34874C9B3AC903F9B92DAB6B970D57769C55F\crlhitbox-0.1.0-SNAPSHOT-dev.jar'
$mcpApi = 'C:\GradleCaches\caches\unimined\maven\net\minecraft\minecraft-cleanroom-0.6.8-alpha-159832fe\1.12.2\minecraft-cleanroom-0.6.8-alpha-159832fe-1.12.2.jar'
$launchWrapperSupportJar = 'D:\WI - Dev Workspace\- test pack\UniversalGravitation\Crl - 0.6.8 - Client\.minecraft\libraries\top\outlands\foundation\0.19.11\foundation-0.19.11.jar'
$verifierShimExternalApiProviderJar = 'D:\WI - Dev Workspace\- test pack\UniversalGravitation\Crl - 0.6.8 - Client\.minecraft\libraries\com\cleanroommc\cleanroom\0.6.8-alpha\cleanroom-0.6.8-alpha.jar'
$mappingRoot = 'C:\GradleCaches\caches\minecraft\de\oceanlabs\mcp\mcp_stable\39\rfg_srgs'
$mcpSrg = Join-Path $mappingRoot 'mcp-srg.srg'
$notchSrg = Join-Path $mappingRoot 'notch-srg.srg'
$classpathJson = 'C:\GradleCaches\artifacts\UniversalGravitation-R113-client-preparation-20261008\manual-launch-candidate-client-b-r1\hmcl-final-classpath-output.json'
$originalClient = 'D:\WI - Dev Workspace\- test pack\UniversalGravitation\Crl - 0.6.8 - Client\.minecraft\versions\1.12.2-Cleanroom\1.12.2-Cleanroom.jar'
$asmRoot = 'D:\WI - Dev Workspace\- test pack\UniversalGravitation\Crl - 0.6.8 - Client\.minecraft\libraries\org\ow2\asm'
$asmCore = Join-Path $asmRoot 'asm\9.10.1\asm-9.10.1.jar'
$asmCommons = Join-Path $asmRoot 'asm-commons\9.10.1\asm-commons-9.10.1.jar'
$asmTree = Join-Path $asmRoot 'asm-tree\9.10.1\asm-tree-9.10.1.jar'
$jdkRoot = 'C:\Program Files\Java\jdk-25.0.2'
$javac = Join-Path $jdkRoot 'bin\javac.exe'
$java = Join-Path $jdkRoot 'bin\java.exe'
$jar = Join-Path $jdkRoot 'bin\jar.exe'
$script:phaseResults = [System.Collections.Generic.List[object]]::new()
$script:logLimitBytes = 4MB

function Get-Sha256([string] $Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToUpperInvariant()
}

function Write-Utf8Json([string] $Path, [object] $Value) {
    $json = $Value | ConvertTo-Json -Depth 12
    [System.IO.File]::WriteAllText($Path, $json + [Environment]::NewLine,
            [System.Text.UTF8Encoding]::new($false))
}

function Limit-Log([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return 0L }
    $bytes = [System.IO.File]::ReadAllBytes($Path)
    $originalLength = $bytes.LongLength
    if ($originalLength -gt $script:logLimitBytes) {
        $half = [int]($script:logLimitBytes / 2)
        $head = [System.Text.Encoding]::UTF8.GetString($bytes, 0, $half)
        $tail = [System.Text.Encoding]::UTF8.GetString($bytes, $bytes.Length - $half, $half)
        $bounded = $head + "`n[log truncated; originalBytes=$originalLength]`n" + $tail
        [System.IO.File]::WriteAllText($Path, $bounded, [System.Text.UTF8Encoding]::new($false))
    }
    return $originalLength
}

function Quote-WindowsArgument([string] $Value) {
    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') { return $Value }
    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.Append('"')
    $slashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') {
            $slashes++
            continue
        }
        if ($character -eq '"') {
            [void]$builder.Append(('\' * (2 * $slashes + 1)))
            [void]$builder.Append('"')
            $slashes = 0
            continue
        }
        if ($slashes -gt 0) { [void]$builder.Append(('\' * $slashes)) }
        [void]$builder.Append($character)
        $slashes = 0
    }
    if ($slashes -gt 0) { [void]$builder.Append(('\' * (2 * $slashes))) }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Invoke-PinnedTool([string] $Phase, [string] $Exe, [string[]] $Arguments) {
    $stdoutPath = Join-Path $evidenceRoot ($Phase + '.stdout.log')
    $stderrPath = Join-Path $evidenceRoot ($Phase + '.stderr.log')
    $quoted = [string]::Join(' ', [string[]]($Arguments | ForEach-Object { Quote-WindowsArgument ([string]$_) }))
    $startedAt = [DateTime]::UtcNow.ToString('o')
    $process = Start-Process -FilePath $Exe -ArgumentList $quoted -WorkingDirectory $repoRoot `
            -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru -Wait -WindowStyle Hidden
    $stdoutBytes = Limit-Log $stdoutPath
    $stderrBytes = Limit-Log $stderrPath
    $entry = [ordered]@{
        phase = $Phase
        executable = $Exe
        arguments = $Arguments
        startedUtc = $startedAt
        finishedUtc = [DateTime]::UtcNow.ToString('o')
        pid = $process.Id
        exitCode = $process.ExitCode
        stdoutPath = $stdoutPath
        stderrPath = $stderrPath
        stdoutOriginalBytes = $stdoutBytes
        stderrOriginalBytes = $stderrBytes
        state = $(if ($process.ExitCode -eq 0) { 'PASS' } else { 'FAILED' })
    }
    $script:phaseResults.Add($entry)
    Write-Utf8Json $statusPath ([ordered]@{ state = 'RUNNING'; revision = 'r5-verifier-shim-field-owner-retest'; phases = $script:phaseResults })
    if ($process.ExitCode -ne 0) {
        throw "Pinned tool phase '$Phase' failed with exit=$($process.ExitCode); stdout=$stdoutPath stderr=$stderrPath"
    }
}

function Require-FileHash([string] $Path, [string] $Expected) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Required pinned file is absent: $Path" }
    $actual = Get-Sha256 $Path
    if ($actual -ne $Expected) { throw "Pinned SHA-256 mismatch for $Path expected=$Expected actual=$actual" }
}

try {
    if (-not (Test-Path -LiteralPath $InputManifest -PathType Leaf)) { throw "Frozen input manifest absent: $InputManifest" }
    if (Test-Path -LiteralPath $buildRoot) { throw "Refusing to overwrite build output: $buildRoot" }
    if (Test-Path -LiteralPath $evidenceRoot) {
        $existingEvidence = @(Get-ChildItem -LiteralPath $evidenceRoot -Force | Where-Object {
            -not [string]::Equals($_.FullName, [System.IO.Path]::GetFullPath($InputManifest),
                    [StringComparison]::OrdinalIgnoreCase)
        })
        if ($existingEvidence.Count -gt 0) {
            throw "Refusing to overwrite existing r5-verifier-shim-field-owner-retest evidence besides the frozen input manifest: $evidenceRoot"
        }
    }
    New-Item -ItemType Directory -Path $buildRoot | Out-Null
    New-Item -ItemType Directory -Path $evidenceRoot | Out-Null

    $manifest = Get-Content -Raw -LiteralPath $InputManifest | ConvertFrom-Json
    foreach ($item in $manifest.files) { Require-FileHash ([string]$item.path) ([string]$item.sha256) }
    $expected = @(
        [ordered]@{ path = $pinnedCandidate; sha256 = 'D88811A34FB415901A9A6622E6200CCB9FC2FDFBADDDE1A79BF9390E2EE412BD' },
        [ordered]@{ path = $pinnedUie; sha256 = 'C8789F51A6AC601AD02EA289742B3FBE1E7F3818581F77CB2AF4E2601BC37A3B' },
        [ordered]@{ path = $pinnedChb; sha256 = '8A322DCE862DA26CCDD7BC7BD390E35F6523D387E57DCECAD225545D3BABE106' },
        [ordered]@{ path = $mcpApi; sha256 = 'F833BB8552A51103F09AED3410030E7818A73359F0878DB6208A451B290D8B71' },
        [ordered]@{ path = $launchWrapperSupportJar; sha256 = 'A9F5CF9CB54715EDF22D6BCB49BAE50F281F818B1731F53F22AAB85A48A7B54B' },
        [ordered]@{ path = $verifierShimExternalApiProviderJar; sha256 = '29E5EC8E02DC7A58F338C53F5D060AAEB7A3742F3DF24C1B3F25ECE1F57CAE1C' },
        [ordered]@{ path = $mcpSrg; sha256 = '3ECD63CDACFDE709D061203C63D4FBE4EB07DF488BABB6E9BBC91CD9F000BFCA' },
        [ordered]@{ path = $notchSrg; sha256 = 'CA7B12A50E7EEA671C129B45A3D430D9873C4EE4B9EB3FCDEE52EF48E382B38C' },
        [ordered]@{ path = $originalClient; sha256 = '8ADA07DA5EE77DAD3527BD7278FBD05EE1FC8A597813B216A871A2D7D64CC64F' },
        [ordered]@{ path = $asmCore; sha256 = 'ED825D10AB1399C8C0CB669E688CF0C8C82629B4C8399B58352B68E92CA10FCB' },
        [ordered]@{ path = $asmCommons; sha256 = '6D0ABEFB7CBF972EA16EDB37EC14835372505063A45F976AB7EA889ED9497895' },
        [ordered]@{ path = $asmTree; sha256 = '3DFB0D5B6A106CD40B5B250E39935FBF2F927F4477546A5369A3AC609CF0506B' },
        [ordered]@{ path = $javac; sha256 = '4058D811CA17644BD9E05614CCA248D5A30E01853B35B1DC94AB3848BF518F93' },
        [ordered]@{ path = $java; sha256 = '63267782812F84001922687FEEAA93C3218C7A1F03409F0650DCDBEF2E7BC8CB' },
        [ordered]@{ path = $jar; sha256 = 'CC2DC7A4185CC45BA87846C38136170A58DA0CF6C94D4CE499DAEA99F177FFFA' }
    )
    foreach ($item in $expected) { Require-FileHash ([string]$item.path) ([string]$item.sha256) }

    $profile = Get-Content -Raw -LiteralPath $classpathJson | ConvertFrom-Json
    if ($profile.finalClasspathCount -ne 135 -or $profile.finalClasspathEntries.Count -ne 135) {
        throw "Pinned HMCL final classpath count mismatch; expected=135 actual=$($profile.finalClasspathEntries.Count)"
    }
    $profileJar = [System.IO.Path]::GetFullPath([string]$profile.versionJarFromRepository)
    if (-not [string]::Equals($profileJar, [System.IO.Path]::GetFullPath($originalClient),
                [StringComparison]::OrdinalIgnoreCase)) {
        throw "Pinned profile original-client path mismatch expected=$originalClient actual=$profileJar"
    }
    $classpathEntries = @($profile.finalClasspathEntries | ForEach-Object { [string]$_ })
    foreach ($entry in $classpathEntries) {
        if (-not (Test-Path -LiteralPath $entry -PathType Leaf)) { throw "Pinned profile classpath file absent: $entry" }
    }
    $requiredShimProviders = [ordered]@{
        'net/minecraft/launchwrapper/Launch.class' = $launchWrapperSupportJar
        'net/minecraft/launchwrapper/LaunchClassLoader.class' = $launchWrapperSupportJar
        'net/minecraftforge/registries/IForgeRegistryEntry.class' = $verifierShimExternalApiProviderJar
        'net/minecraftforge/registries/IForgeRegistryEntry$Impl.class' = $verifierShimExternalApiProviderJar
        'net/minecraftforge/registries/IRegistryDelegate.class' = $verifierShimExternalApiProviderJar
    }
    $shimProviderEntries = @($requiredShimProviders.Keys)
    $shimProfileProviders = [ordered]@{}
    foreach ($classEntry in $shimProviderEntries) { $shimProfileProviders[$classEntry] = [System.Collections.Generic.List[string]]::new() }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach ($classpathEntry in $classpathEntries) {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($classpathEntry)
        try {
            foreach ($classEntry in $shimProviderEntries) {
                if ($null -ne $archive.GetEntry($classEntry)) { $shimProfileProviders[$classEntry].Add($classpathEntry) }
            }
        }
        finally { $archive.Dispose() }
    }
    foreach ($classEntry in $shimProviderEntries) {
        $providers = @($shimProfileProviders[$classEntry])
        $expectedProvider = [string]$requiredShimProviders[$classEntry]
        if ($providers.Count -ne 1 -or -not [string]::Equals(
                [System.IO.Path]::GetFullPath($providers[0]), [System.IO.Path]::GetFullPath($expectedProvider),
                [StringComparison]::OrdinalIgnoreCase)) {
            throw "Pinned profile must provide exactly one verifier-shim dependency class $classEntry from $expectedProvider; providers=$($providers -join ';')"
        }
    }
    Invoke-PinnedTool '00-java-version' $java @('-version')
    Invoke-PinnedTool '00-javac-version' $javac @('-version')
    Invoke-PinnedTool '00-jar-version' $jar @('--version')
    $javaVersion = ((Get-Content -Raw -LiteralPath (Join-Path $evidenceRoot '00-java-version.stdout.log')) +
            (Get-Content -Raw -LiteralPath (Join-Path $evidenceRoot '00-java-version.stderr.log'))).Trim()
    $javacVersion = ((Get-Content -Raw -LiteralPath (Join-Path $evidenceRoot '00-javac-version.stdout.log')) +
            (Get-Content -Raw -LiteralPath (Join-Path $evidenceRoot '00-javac-version.stderr.log'))).Trim()
    $jarVersion = ((Get-Content -Raw -LiteralPath (Join-Path $evidenceRoot '00-jar-version.stdout.log')) +
            (Get-Content -Raw -LiteralPath (Join-Path $evidenceRoot '00-jar-version.stderr.log'))).Trim()
    if ($javacVersion -ne 'javac 25.0.2' -or $jarVersion -ne 'jar 25.0.2' -or $javaVersion -notmatch '25\.0\.2') {
        throw "Pinned Java 25 tool versions unexpected: java='$javaVersion' javac='$javacVersion' jar='$jarVersion'"
    }

    $sourceRoot = Join-Path $toolRoot 'src'
    $agentSource = Join-Path $sourceRoot 'agent\java\wi\unigrav\probe\agent\AcceptedConsumerProbeAgent.java'
    $modSource = Join-Path $sourceRoot 'main\java\wi\unigrav\probe\AcceptedConsumerProbeMod.java'
    $hooksSource = Join-Path $sourceRoot 'main\java\wi\unigrav\probe\ProbeHooks.java'
    $remapperSource = Join-Path $sourceRoot 'test\java\wi\unigrav\probe\agent\ProbeMcpSrgRemapperMain.java'
    $preflightSource = Join-Path $sourceRoot 'test\java\wi\unigrav\probe\agent\ProbeBytecodePreflightMain.java'
    $agentClasses = Join-Path $buildRoot 'agent-classes'
    $modClasses = Join-Path $buildRoot 'mod-mcp-classes'
    $toolClasses = Join-Path $buildRoot 'tool-classes'
    $agentJar = Join-Path $buildRoot 'accepted-consumer-probe-agent-r5-verifier-shim-field-owner-retest.jar'
    $modMcpJar = Join-Path $buildRoot 'accepted-consumer-probe-mcp-r5-verifier-shim-field-owner-retest.jar'
    $probeSrgJar = Join-Path $buildRoot 'accepted-consumer-probe-srg-r5-verifier-shim-field-owner-retest.jar'
    $shimJar = Join-Path $buildRoot 'srg-api-verifier-shim-r5-verifier-shim-field-owner-retest.jar'
    $membersReport = Join-Path $evidenceRoot 'exact-mcp-srg-notch-member-report.tsv'
    $sourceClassPath = @($pinnedCandidate, $pinnedUie, $pinnedChb, $mcpApi) + $classpathEntries
    $modClassPath = [string]::Join(';', [string[]]$sourceClassPath)
    $asmClassPath = [string]::Join(';', [string[]]@($asmCore, $asmCommons, $asmTree))
    $javaTemp = Join-Path ([System.IO.Path]::GetTempPath()) ("ug-accepted-probe-r5-verifier-shim-field-owner-retest-" + $PID)
    New-Item -ItemType Directory -Path $javaTemp | Out-Null
    $script:runContext = [ordered]@{
        revision = 'r5-verifier-shim-field-owner-retest'
        state = 'RUNNING'
        scope = 'compile-remap-bytecode-preflight-only'
        runner = $PSCommandPath
        runnerSha256 = Get-Sha256 $PSCommandPath
        inputManifestPath = [System.IO.Path]::GetFullPath($InputManifest)
        inputManifestSha256 = Get-Sha256 $InputManifest
        repositoryRoot = $repoRoot
        buildRoot = $buildRoot
        evidenceRoot = $evidenceRoot
        javaTemp = $javaTemp
        javaVersion = $javaVersion
        javacVersion = $javacVersion
        jarVersion = $jarVersion
        profileClasspathCount = $classpathEntries.Count
        pinnedOriginalClientJar = $originalClient
        verifierShimExternalProfileProviders = [ordered]@{
            launchWrapperJarPath = $launchWrapperSupportJar
            launchWrapperJarSha256 = Get-Sha256 $launchWrapperSupportJar
            externalApiJarPath = $verifierShimExternalApiProviderJar
            externalApiJarSha256 = Get-Sha256 $verifierShimExternalApiProviderJar
            profileProviders = $shimProfileProviders
        }
        phases = $script:phaseResults
    }
    Write-Utf8Json $statusPath $script:runContext

    New-Item -ItemType Directory -Path $agentClasses,$modClasses,$toolClasses | Out-Null
    Invoke-PinnedTool '01-javac-agent' $javac @('--release','25','-encoding','UTF-8','-proc:none','-classpath',$asmCore,'-d',$agentClasses,$agentSource)
    $agentManifest = Join-Path $buildRoot 'agent-manifest.mf'
    [System.IO.File]::WriteAllText($agentManifest,"Manifest-Version: 1.0`r`nPremain-Class: wi.unigrav.probe.agent.AcceptedConsumerProbeAgent`r`n`r`n",[System.Text.UTF8Encoding]::new($false))
    Invoke-PinnedTool '02-jar-agent' $jar @('--create','--file',$agentJar,'--manifest',$agentManifest,'-C',$agentClasses,'.')
    Invoke-PinnedTool '03-javac-mcp-mod' $javac @('--release','25','-encoding','UTF-8','-proc:none','-classpath',$modClassPath,'-d',$modClasses,$modSource,$hooksSource)
    $modResources = Join-Path $sourceRoot 'main\resources'
    Invoke-PinnedTool '04-jar-mcp-mod' $jar @('--create','--file',$modMcpJar,'-C',$modClasses,'.','-C',$modResources,'.')
    Invoke-PinnedTool '05-javac-remap-preflight' $javac @('--release','25','-encoding','UTF-8','-proc:none','-classpath',$asmClassPath,'-d',$toolClasses,$remapperSource,$preflightSource)
    $toolClassPath = [string]::Join(';',[string[]]@($toolClasses,$asmCore,$asmCommons,$asmTree))
    Invoke-PinnedTool '06-mcp-srg-notch-abi-remap' $java @('-Dfile.encoding=UTF-8',("-Djava.io.tmpdir=" + $javaTemp),'-classpath',$toolClassPath,'wi.unigrav.probe.agent.ProbeMcpSrgRemapperMain',$modMcpJar,$mcpSrg,$mcpApi,$notchSrg,$originalClient,$probeSrgJar,$shimJar,$membersReport,$verifierShimExternalApiProviderJar)

    $preflightCp = @($toolClasses,$probeSrgJar,$shimJar,$pinnedCandidate,$pinnedUie,$pinnedChb,$agentJar,$asmCore,$asmCommons,$asmTree) + $classpathEntries
    $runtimeClassPath = [string]::Join(';',[string[]]$preflightCp)
    Invoke-PinnedTool '07-java-bytecode-preflight' $java @('-Xverify:all','-Dfile.encoding=UTF-8',("-Djava.io.tmpdir=" + $javaTemp),("-Dug.acceptedProbe.dir=" + $evidenceRoot),("-javaagent:" + $agentJar),'-classpath',$runtimeClassPath,'wi.unigrav.probe.agent.ProbeBytecodePreflightMain')
    $agentTargets = Join-Path $evidenceRoot 'agent-targets.txt'
    if (-not (Test-Path -LiteralPath $agentTargets -PathType Leaf)) { throw "Agent did not write transform target summary: $agentTargets" }
    $targetSummary = Get-Content -Raw -LiteralPath $agentTargets
    if (($targetSummary -notmatch '(?m)^state=OBSERVED$') -or ($targetSummary -match '(?m)^state=FAILED$') -or ($targetSummary -match '(?m)state=UNMATCHED')) {
        throw "Bytecode transformer did not match every required target exactly once; see $agentTargets"
    }
    if (Test-Path -LiteralPath (Join-Path $evidenceRoot 'agent-failed.txt')) {
        throw "Bytecode transformer reported FAILED; see agent-failed.txt and bounded phase logs in $evidenceRoot"
    }
    $abiReport = Get-Content -Raw -LiteralPath $membersReport
    if ($abiReport -notmatch '(?m)^state=PASS$' -or $abiReport -notmatch '(?m)^verifierShimState=GENERATED_NOT_RUNTIME_ORACLE$') {
        throw "ABI/remap report lacks the required structural state: $membersReport"
    }
    $externalShimRows = @($abiReport -split '\r?\n' | Where-Object {
        $_.StartsWith(('net/minecraft/item/crafting/IRecipe' + [char]9 + 'getRegistryName' + [char]9))
    })
    $fieldShimRows = @($abiReport -split '\r?\n' | Where-Object {
        $_.StartsWith(('net/minecraft/potion/Potion' + [char]9 + 'delegate' + [char]9))
    })
    if ($externalShimRows.Count -ne 1 -or $fieldShimRows.Count -ne 1) {
        throw "ABI report must contain exactly the two observed verifier-shim external dependencies; methodCount=$($externalShimRows.Count) fieldCount=$($fieldShimRows.Count)"
    }
    $externalShimColumns = $externalShimRows[0].Split([char]9)
    if ($externalShimColumns.Count -ne 18) { throw 'Method ABI row has an unexpected column count.' }
    $methodMcpOwnerAccess = [int]$externalShimColumns[6]
    $methodMcpDeclarationClassAccess = [int]$externalShimColumns[5]
    $methodRuntimeAccess = [int]$externalShimColumns[13]
    $methodRuntimeClassAccess = [int]$externalShimColumns[14]
    $methodOriginalOwnerAccess = [int]$externalShimColumns[16]
    if ($externalShimColumns.Count -ne 18 -or
            $externalShimColumns[3] -ne 'net/minecraftforge/registries/IForgeRegistryEntry' -or
            $externalShimColumns[8] -ne 'net/minecraftforge/registries/IForgeRegistryEntry' -or
            $externalShimColumns[9] -ne 'getRegistryName' -or
            $externalShimColumns[10] -ne '()Lnf;' -or
            $externalShimColumns[11] -ne 'NOTCH' -or
            $externalShimColumns[12] -ne '()Lnet/minecraft/util/ResourceLocation;' -or
            (($methodMcpOwnerAccess -band 513) -ne 513) -or
            (($methodOriginalOwnerAccess -band 513) -ne 513) -or
            (($methodOriginalOwnerAccess -band 512) -ne 512) -or
            (($methodMcpDeclarationClassAccess -band 513) -ne 513) -or
            (($methodRuntimeClassAccess -band 513) -ne 513) -or
            (($methodRuntimeAccess -band 1025) -ne 1025) -or
            (($methodRuntimeAccess -band 8) -ne 0) -or
            [string]::IsNullOrWhiteSpace($externalShimColumns[15]) -or
            @('GAP_NOT_PRESENT_IN_PINNED_ORIGINAL_CLIENT_DIRECT_DECLARATIONS',
                'DIRECT_PRESENT_PROVIDER_DESCRIPTOR_ACCESS_MATCH') -notcontains $externalShimColumns[17]) {
        throw "ABI report method external provider declaration does not match the pinned byte namespace and payload SRG contract: $($externalShimRows[0])"
    }
    $fieldShimColumns = $fieldShimRows[0].Split([char]9)
    if ($fieldShimColumns.Count -ne 18) { throw 'Field ABI row has an unexpected column count.' }
    $delegateDescriptor = 'Lnet/minecraftforge/registries/IRegistryDelegate;'
    $implOwner = 'net/minecraftforge/registries/IForgeRegistryEntry$Impl'
    $fieldMcpAccess = [int]$fieldShimColumns[4]
    $fieldMcpClassAccess = [int]$fieldShimColumns[5]
    $fieldRuntimeAccess = [int]$fieldShimColumns[13]
    $fieldRuntimeClassAccess = [int]$fieldShimColumns[14]
    $fieldOriginalOwnerAccess = [int]$fieldShimColumns[16]
    $fieldMcpSymbolicOwnerAccess = [int]$fieldShimColumns[6]
    if ($fieldShimColumns.Count -ne 18 -or
            $fieldShimColumns[2] -ne $delegateDescriptor -or
            $fieldShimColumns[3] -ne $implOwner -or
            $fieldShimColumns[8] -ne $implOwner -or
            $fieldShimColumns[9] -ne 'delegate' -or
            $fieldShimColumns[10] -ne $delegateDescriptor -or
            $fieldShimColumns[11] -ne 'EXTERNAL_IDENTITY' -or
            $fieldShimColumns[12] -ne $delegateDescriptor -or
            (($fieldMcpAccess -band 17) -ne 18) -or (($fieldMcpAccess -band 8) -ne 0) -or
            (($fieldRuntimeAccess -band 17) -ne 18) -or (($fieldRuntimeAccess -band 8) -ne 0) -or
            (($fieldMcpClassAccess -band 1) -eq 0) -or (($fieldMcpClassAccess -band 1536) -ne 0) -or
            (($fieldRuntimeClassAccess -band 1) -eq 0) -or (($fieldRuntimeClassAccess -band 1536) -ne 0) -or
            [string]::IsNullOrWhiteSpace($fieldShimColumns[15]) -or
            (($fieldOriginalOwnerAccess -band 1) -eq 0) -or (($fieldOriginalOwnerAccess -band 512) -ne 0) -or
            (($fieldMcpSymbolicOwnerAccess -band 512) -ne 0) -or
            (($fieldMcpSymbolicOwnerAccess -band 1) -eq 0) -or
            (($fieldMcpSymbolicOwnerAccess -band 512) -ne ($fieldOriginalOwnerAccess -band 512)) -or
            @('GAP_NOT_PRESENT_IN_PINNED_ORIGINAL_CLIENT_DIRECT_DECLARATIONS',
                'DIRECT_PRESENT_PUBLIC_FINAL_INSTANCE_MATCH') -notcontains $fieldShimColumns[17]) {
        throw "ABI report field external declaration does not match the pinned public-final-instance Forge provider contract: $($fieldShimRows[0])"
    }
    $typeRows = @($abiReport -split '\r?\n' | Where-Object {
        $_.StartsWith(('net/minecraftforge/registries/IForgeRegistryEntry$Impl' + [char]9)) -or
        $_.StartsWith(('net/minecraftforge/registries/IRegistryDelegate' + [char]9))
    })
    if ($typeRows.Count -ne 2) { throw "ABI report must contain both inspected Forge external types; count=$($typeRows.Count)" }
    $implTypeRows = @($typeRows | Where-Object { $_.StartsWith(('net/minecraftforge/registries/IForgeRegistryEntry$Impl' + [char]9)) })
    $delegateTypeRows = @($typeRows | Where-Object { $_.StartsWith(('net/minecraftforge/registries/IRegistryDelegate' + [char]9)) })
    if ($implTypeRows.Count -ne 1 -or $delegateTypeRows.Count -ne 1) {
        throw 'ABI report is missing a unique IForgeRegistryEntry$Impl or IRegistryDelegate class-shape row.'
    }
    $implType = $implTypeRows[0].Split([char]9)
    $delegateType = $delegateTypeRows[0].Split([char]9)
    $delegateMcpAccess = [int]$delegateType[1]
    $delegateRuntimeAccess = [int]$delegateType[2]
    if ($implType.Count -ne 4 -or $implType[3] -ne 'CLASS' -or
            $delegateType.Count -ne 4 -or $delegateType[3] -ne 'INTERFACE' -or
            (($delegateMcpAccess -band 1537) -ne 1537) -or
            (($delegateRuntimeAccess -band 1537) -ne 1537)) {
        throw "ABI report does not prove the expected $implOwner class and public IRegistryDelegate interface shapes."
    }
    $script:runContext.verifierShimExternalMemberEvidence = @($externalShimRows[0], $fieldShimRows[0], $implTypeRows[0], $delegateTypeRows[0])
    $script:runContext.abiReportSha256 = Get-Sha256 $membersReport
    Write-Utf8Json $statusPath $script:runContext
    Write-Utf8Json $handoffPath $script:runContext
    Write-Output "R5_VERIFIER_SHIM_FIELD_OWNER_RETEST_BYTECODE_PREFLIGHT_PASS build=$buildRoot evidence=$evidenceRoot abiReportSha256=$($script:runContext.abiReportSha256)"
}
catch {
    $failure = $_
    $context = if ($script:runContext) { $script:runContext } else { [ordered]@{ revision = 'r5-verifier-shim-field-owner-retest'; scope = 'compile-remap-bytecode-preflight-only' } }
    $context.state = 'FAILED'
    $context.failure = [ordered]@{
        message = $failure.Exception.Message
        exceptionType = $failure.Exception.GetType().FullName
        exceptionToString = $failure.Exception.ToString()
        scriptStackTrace = $failure.ScriptStackTrace
        fullyQualifiedErrorId = $failure.FullyQualifiedErrorId
    }
    $context.phases = $script:phaseResults
    if (Test-Path -LiteralPath $evidenceRoot) {
        Write-Utf8Json $statusPath $context
        [System.IO.File]::WriteAllText((Join-Path $evidenceRoot 'FAILED.txt'),
                "state=FAILED`nmessage=$($failure.Exception.Message)`nexceptionToString=$($failure.Exception.ToString())`nscriptStackTrace=$($failure.ScriptStackTrace)`n",
                [System.Text.UTF8Encoding]::new($false))
    }
    throw
}
