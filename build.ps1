param(
    [switch]$IncrementPatch,
    [switch]$IncrementMinor,
    [switch]$IncrementMajor,
    [switch]$IncrementCandidate,
    [switch]$RemoveCandidate,
    [string]$UpdateMinecraft = "",
    [string]$Reason = "",
    [switch]$CommitChanges,
    [switch]$BuildPack,
    [switch]$DryRun,
    [switch]$TagRelease
)

# Utility: read properties into hashtable preserving comments separately
function Read-VersionProps {
    param([string]$Path)
    if (-not (Test-Path $Path)) { throw "version.properties not found at $Path" }
    $raw = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 -ErrorAction Stop
    $lines = $raw -split "`r?`n"
    $props = @{}
    foreach ($ln in $lines) {
        if ($ln -match '^[\s]*#') { continue }
        if ($ln -match '^[\s]*$') { continue }
        $kv = $ln -split '=', 2
        if ($kv.Length -eq 2) {
            $key = $kv[0].Trim()
            $val = $kv[1].Trim()
            $props[$key] = $val
        }
    }
    return $props
}

function Write-VersionProps {
    param([string]$Path, [hashtable]$Props)
    $template = @(
        '# AnvilUnlocked Version Configuration',
        '# Format: MinecraftVersion_PluginVersion (e.g., 1.21.8_1.3.0)',
        '',
        '# Minecraft version this plugin targets',
        "minecraft=$($Props['minecraft'])",
        '',
        '# Plugin version (major.minor.patch)',
        "plugin_major=$($Props['plugin_major'])",
        "plugin_minor=$($Props['plugin_minor'])",
        "plugin_patch=$($Props['plugin_patch'])"
    )
    if ($Props.ContainsKey('plugin_candidate') -and $Props['plugin_candidate']) {
        $template += "plugin_candidate=$($Props['plugin_candidate'])"
    }
    $template = $template | Where-Object { $_ -ne $null }
    # Backup
    $backup = "$Path.bak"
    if (Test-Path $Path) { Copy-Item -LiteralPath $Path -Destination $backup -Force }
    try {
        Set-Content -LiteralPath $Path -Value ($template -join "`r`n") -Encoding UTF8 -NoNewline
    }
    catch {
        if (Test-Path $backup) { Copy-Item -LiteralPath $backup -Destination $Path -Force }
        throw
    }
    finally {
        if (Test-Path $backup) { Remove-Item -LiteralPath $backup -Force }
    }
}

function Convert-Candidate([string]$cand) {
    if (-not $cand) { return $null }
    # Accept rc1 or rc.1; persist as rc1
    if ($cand -match 'rc\.?([0-9]+)') { return "rc$($Matches[1])" }
    return $cand
}

# Ensure only one increment option is used
$incrementSwitches = @($IncrementPatch, $IncrementMinor, $IncrementMajor, $IncrementCandidate, $RemoveCandidate) | Where-Object { $_ }
if ($incrementSwitches.Count -gt 1) {
    throw 'Specify only one of -IncrementPatch, -IncrementMinor, -IncrementMajor, -IncrementCandidate, -RemoveCandidate.'
}

$versionFile = Join-Path $PSScriptRoot 'version.properties'
$props = Read-VersionProps -Path $versionFile

# Defaults and normalization
if (-not $props.ContainsKey('minecraft')) { $props['minecraft'] = '1.21.1' }
foreach ($k in 'plugin_major', 'plugin_minor', 'plugin_patch') { if (-not $props.ContainsKey($k)) { $props[$k] = '0' } }
if ($props.ContainsKey('plugin_candidate')) { $props['plugin_candidate'] = Convert-Candidate $props['plugin_candidate'] }

# Apply explicit Minecraft version update
if ($UpdateMinecraft) { $props['minecraft'] = $UpdateMinecraft }

# Version bump logic
if ($IncrementMajor) {
    $props['plugin_major'] = ([int]$props['plugin_major'] + 1).ToString()
    $props['plugin_minor'] = '0'
    $props['plugin_patch'] = '0'
    $props.Remove('plugin_candidate')
}
elseif ($IncrementMinor) {
    $props['plugin_minor'] = ([int]$props['plugin_minor'] + 1).ToString()
    $props['plugin_patch'] = '0'
    $props.Remove('plugin_candidate')
}
elseif ($IncrementPatch) {
    $props['plugin_patch'] = ([int]$props['plugin_patch'] + 1).ToString()
    $props.Remove('plugin_candidate')
}
elseif ($IncrementCandidate) {
    if ($props.ContainsKey('plugin_candidate') -and $props['plugin_candidate']) {
        if ($props['plugin_candidate'] -match 'rc([0-9]+)') {
            $props['plugin_candidate'] = "rc$([int]$Matches[1] + 1)"
        }
        else {
            $props['plugin_candidate'] = 'rc1'
        }
    }
    else {
        $props['plugin_candidate'] = 'rc1'
    }
}
elseif ($RemoveCandidate) {
    if ($props.ContainsKey('plugin_candidate')) { $props.Remove('plugin_candidate') }
}

# Compute version string for logging/commit/tag
$mc = $props['minecraft']
$maj = $props['plugin_major']
$min = $props['plugin_minor']
$pat = $props['plugin_patch']
$cand = $props['plugin_candidate']
$plugin = if ($cand) { "$maj.$min.$pat.$cand" } else { "$maj.$min.$pat" }
$verString = "${mc}_${plugin}"

if ($DryRun) {
    Write-Host "[DryRun] Would update version.properties to:" -ForegroundColor Yellow
    Write-Host "  minecraft=$mc" -ForegroundColor Yellow
    Write-Host "  plugin_major=$maj" -ForegroundColor Yellow
    Write-Host "  plugin_minor=$min" -ForegroundColor Yellow
    Write-Host "  plugin_patch=$pat" -ForegroundColor Yellow
    if ($cand) { Write-Host "  plugin_candidate=$cand" -ForegroundColor Yellow } else { Write-Host "  plugin_candidate=(none)" -ForegroundColor Yellow }
}
else {
    # Write back
    Write-VersionProps -Path $versionFile -Props $props
}

# Optional Git integration
if ($CommitChanges) {
    if ($DryRun) {
        Write-Host "[DryRun] Would commit version bump: $verString" -ForegroundColor Yellow
    }
    else {
        git add version.properties | Out-Null
        $msg = if ($Reason) { "Bump version to $verString - $Reason" } else { "Bump version to $verString" }
        git commit -m $msg | Out-Null
        if ($TagRelease) {
            git tag "v$verString" | Out-Null
        }
    }
}

# Optional: build pack hook placeholder
if ($BuildPack) {
    Write-Host 'Build pack requested (-BuildPack). Add your resource pack build steps here.'
}

if ($DryRun) {
    Write-Host "Dry run complete. No files were modified." -ForegroundColor Green
}
else {
    Write-Host "Updated version.properties to $verString." -ForegroundColor Green
}
