# AnvilsUnlocked

A Paper 1.21.1+ plugin that removes the anvil's 40-level cap while keeping vanilla mechanics.

- Right-click an anvil as usual; the UI behaves like vanilla but allows costs > 40.
- Conflicting enchants: right input overrides left on conflict.
- Prior work is factored in (2^uses − 1), book halving, merge rules, repair rules.

## Build prerequisites
- Java 21 (JDK) on PATH
- Gradle Wrapper (auto) or local Gradle to generate the wrapper

## Build steps (Windows)
1) If you have local Gradle, run the VS Code task: “Gradle Wrapper (Bootstrap)” (or `gradle wrapper`).
2) Build the plugin using the wrapper:
	 - VS Code task: “Gradle Build (AnvilsUnlocked)”, or
	 - Terminal:
		 ```powershell
		 .\gradlew.bat clean build -x test
		 ```

Output jar: `build\libs\AnvilsUnlocked-<version>.jar`.

## Versioning
Project version is composed from `version.properties` as `Minecraft_Plugin` (e.g., `1.21.8_1.6.0.rc1`).
Use the PowerShell helper to bump versions:

```powershell
# Preview (no changes)
./build.ps1 -DryRun

# Patch bump and commit
./build.ps1 -IncrementPatch -Reason "Fix" -CommitChanges

# Release candidate
./build.ps1 -IncrementCandidate -Reason "Beta"

# Update Minecraft target
./build.ps1 -UpdateMinecraft "1.21.9"
```
