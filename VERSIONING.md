# AnvilUnlocked Versioning Guide

## Version Format
**Format**: `MinecraftVersion_PluginVersion`  
- Example: `1.21.8_1.6.0.rc1`

## Version Components

### Minecraft Version (1.21.8)
- Should match the target Minecraft version
- Update when upgrading compatibility to new Minecraft releases
- Currently targeting: **1.21.8**

### Plugin Version (1.6.0.rc1)
**Custom Versioning**: `MAJOR.MINOR.PATCH.CANDIDATE`  

#### MAJOR Version (1.x.x.x)
Increment when making **incompatible API changes** or **breaking changes**:
- Breaking configuration format changes
- Incompatible data file format changes  
- Removal of major features
- Complete system redesigns  
- Resets MINOR, PATCH, and CANDIDATE versions to `0`

#### MINOR Version (x.6.x.x)
Increment when adding **new features** in a backwards-compatible manner:
- New commands or functionality
- New configuration options
- New game mechanics (camp system, banner system)
- Significant UX improvements
- New resource pack features  
- Resets PATCH and CANDIDATE versions to `0`

#### PATCH Version (x.x.0.x)
Increment for **backwards-compatible bug fixes**:
- Bug fixes that don't change functionality
- Performance improvements
- Code cleanup without behavior changes
- Documentation updates  
- Removes CANDIDATE version.

#### CANDIDATE Version (x.x.x.rc1)
Used for pre-release versions (e.g., `rc1`):
- Increment candidate for testing or preview releases
- Removed when final release is ready

## Version Update Process

### Manual Version Updates
1. **Edit `version.properties`**:
   ```properties
   minecraft=1.21.8
   plugin_major=1
   plugin_minor=6  
   plugin_patch=0
   plugin_candidate=rc1
   ```

2. **Commit with descriptive message**:
   ```bash
   git commit -m "Bump version to 1.21.8_1.6.0.rc1 - [reason for version bump]"
   ```

### Automated Version Updates
Use PowerShell build scripts for automatic incrementing:

```powershell
# Increment patch version (1.6.0 → 1.6.1) and (1.6.1.rc1 → 1.6.1)
.\build.ps1 -IncrementPatch

# Increment minor version (1.6.0 → 1.7.0) and (1.6.1.rc1 →  1.7.0) 
.\build.ps1 -IncrementMinor

# Increment major version (1.6.0 → 2.0.0) and (1.6.1.rc1 →  2.0.0)
.\build.ps1 -IncrementMajor

# Release candidate versions (1.6.1 → 1.6.1.rc1) and (1.6.1.rc1 → 1.6.1.rc2)
.\build.ps1 -IncrementCandidate

# Update Minecraft version
.\build.ps1 -UpdateMinecraft "1.21.9"            # Updates minecraft target
```

#### Building the Version Bump Script
When creating `build.ps1`, include these key components:

**Parameters to support:**
```powershell
param(
    [switch]$IncrementPatch,
    [switch]$IncrementMinor, 
    [switch]$IncrementMajor,
    [switch]$IncrementCandidate,
    [switch]$RemoveCandidate,
    [string]$UpdateMinecraft = "",
    [string]$Reason = "",
    [switch]$CommitChanges,
    [switch]$BuildPack
)
```

**Required functionality:**
1. **Read version.properties** - Parse current version values
2. **Validate inputs** - Ensure only one increment type is specified
3. **Version logic:**
   - PATCH: increment patch, remove candidate
   - MINOR: increment minor, reset patch, remove candidate  
   - MAJOR: increment major, reset minor/patch, remove candidate
   - CANDIDATE: increment candidate version (rc1→rc2) or add if none exists
   - Handle release candidate versions (rc1, rc2, rc3, etc.)
4. **Write version.properties** - Update with new values preserving comments
5. **Git integration** (optional):
   - Stage version.properties changes
   - Create commit with standardized message format
   - Create git tag (optional)
6. **Error handling** - Validate file exists, backup on failure
7. **Dry run mode** - Show what changes would be made

**Example implementation structure:**
```powershell
# Read current version from version.properties
$versionFile = "version.properties"
$props = Get-Content $versionFile | ConvertFrom-StringData

# Apply version logic
if ($IncrementPatch) {
    $props.plugin_patch = [int]$props.plugin_patch + 1
    $props.Remove("plugin_candidate")
}
elseif ($IncrementCandidate) {
    if ($props.ContainsKey("plugin_candidate")) {
        # Extract number and increment (rc1 → rc2)
        $props.plugin_candidate = "rc$([int]($props.plugin_candidate -replace '\D','') + 1)"
    } else {
        $props.plugin_candidate = "rc1"
    }
}
# ... other increment logic

# Write back to file preserving format
# Optional: git commit with standardized message
# Optional: build JAR or resource pack
```

## Version History Examples

### Recent Version Changes
- **1.21.8_1.5.0**: Banner persistence system, non-throwable tokens
- **1.21.8_1.6.0**: Direct token consumption system (removed addtoken command)

### When to Increment Which Version

#### PATCH Examples (x.x.+1):
- Fixed banner colors not saving correctly
- Improved performance of waypoint loading
- Fixed typo in help messages

#### MINOR Examples (x.+1.0):
- Added camp banner protection system
- Implemented custom token appearance
- Added reload commands
- Introduced direct token consumption

#### MAJOR Examples (+1.0.0):
- Complete rewrite from ender pearl tokens to paper tokens
- Breaking change to data file format
- Removal of old waypoint system
- API breaking changes for other plugins

## Release Checklist
- [ ] Update version in `version.properties` (manual) OR use `.\build.ps1 -IncrementPatch/Minor/Major`
- [ ] Test build: `.\gradlew clean build`
- [ ] Update changelog/commit message with version bump reason
- [ ] Commit changes: `git commit -m "Bump version to 1.21.8_1.6.1 - [reason]"`
- [ ] Tag release (optional): `git tag v1.21.8_1.6.1`
- [ ] Build final JAR: `.\build.ps1 -BuildPack` (if resource pack needed)

### Automated Release Workflow
When the build script is implemented, the release process becomes:

```powershell
# Example: Bug fix release
.\build.ps1 -IncrementPatch -Reason "Fixed banner persistence bug" -CommitChanges

# Example: Feature release  
.\build.ps1 -IncrementMinor -Reason "Added waypoint categories" -CommitChanges

# Example: Release candidate
.\build.ps1 -IncrementCandidate -Reason "Beta testing new camp system"

# Example: Final release (remove candidate)
.\build.ps1 -RemoveCandidate -CommitChanges
```

## Current Version
**Latest**: `1.21.8_1.0.0.rc1`  
**Minecraft Target**: 1.21.8+
**Paper Target**: Paper 1.21.1+  
**Java**: 21+ (required for all builds)
```