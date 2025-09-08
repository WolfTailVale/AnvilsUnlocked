# AnvilsUnlocked

Paper 1.21.1+ plugin that removes the anvil’s 40-level cap while keeping vanilla mechanics.

Highlights
- Preserves vanilla logic: prior work (2^uses − 1), book halving, merge rules, unit-material and same-type repairs.
- Conflict rule: right input overrides left on incompatible enchants.
- Costs can exceed 40; transactions succeed if the player has enough levels.
- Clear cost display via a BossBar overlay (the vanilla UI will still show “Too Expensive!” text).

Requirements
- Paper 1.21.1+ (Java 21)

Install
- Drop the jar into your server’s `plugins/` folder and restart.

Build (Windows)
- With the Gradle wrapper:
	```powershell
	.\gradlew.bat clean reobfJar
	```
- Output: `build\libs\AnvilsUnlocked-<version>.jar`

Versioning
- Version comes from `version.properties` (format: `Minecraft_Plugin`, e.g., `1.21.8_1.6.0.rc1`).
- Helper script (optional):
	```powershell
	./build.ps1 -DryRun
	./build.ps1 -IncrementPatch -Reason "Fix" -CommitChanges
	./build.ps1 -IncrementCandidate -Reason "Beta"
	./build.ps1 -UpdateMinecraft "1.21.9"
	```

License
- See `LICENSE`.
