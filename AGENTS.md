# WorldPrune — Agent Reference

This file is the primary reference for LLM agents working on this codebase. Read it before making changes.

## Non-Negotiable Rules

1. **Test locally before committing or pushing.** Always run the relevant test suite against the actual code before sending changes to the remote. For unit test changes: `./gradlew test`. For anything touching integration scripts, Gradle tasks, plugin behaviour, or CI: `./gradlew integration`. If it doesn't pass locally, do not push.
2. **Never push a fix for a CI failure without first reproducing the fix locally.**
3. **Every commit must follow [Conventional Commits](https://www.conventionalcommits.org/).** The commit message format drives automatic semantic versioning on merge to `main`. The `commit-msg` hook enforces this locally; CI enforces it on PRs. See [Conventional Commits](#conventional-commits) for the required format.

## Quick Start
- **Build**: `./gradlew build`
- **Output JAR**: `build/libs/world-prune-plugin-<version>.jar`
- **Package**: `dev.minecraft.prune`
- **Main class**: `WorldPrunePlugin`
- **Config**: `src/main/resources/config.yml`
- **Manifest**: `src/main/resources/plugin.yml`

## Architecture Overview

The plugin uses an **artifact-first model**: all planning produces frozen snapshots stored in `<plugin-data>/reports/<planId>/`.

```
WorldPrunePlugin (bootstrap)
├── PlanService (claims-based planning)
├── HeuristicService (size/entity-aware filtering)
├── ApplyService (quarantine moves + stale lock recovery)
├── RestoreService (quarantine rollback)
├── PurgeService (permanent quarantine deletion)
├── ScheduleService (automated scheduled scan + apply)
├── PlanStore (artifact metadata)
├── ClaimBoundsProvider (GriefPrevention + file parsing)
└── PruneCommand (CLI dispatch)
```

## Command Model

All commands are read-only during planning phases. Destructive actions require a two-step staged confirmation (`/prune confirm`).

```
/prune scan [world]              — analyse world and generate a plan
/prune plans [world]             — list stored plans
/prune plan <planId>             — show plan details
/prune apply [world] [--force-unlock]  — preview apply and stage for confirmation
/prune confirm                   — execute the staged action (30 s window)
/prune undo [world] [<apply-id>] — restore from quarantine
/prune quarantine [world]        — list quarantine directories
/prune drop <world> <apply-id>   — preview permanent delete and stage for confirmation
/prune map [world] [planId]      — give yourself a FILLED_MAP item showing the keep/prune grid
/prune status
```

## Key Files & Responsibilities

| File | Purpose |
|------|---------|
| [PlanService.java](src/main/java/dev/minecraft/prune/PlanService.java) | Claims-based planning (GriefPrevention API + file fallback + manual keep merge). | Outputs: keep-regions, prune-candidates, kept-existing, summary. Saves metadata to PlanStore. |
| [HeuristicService.java](src/main/java/dev/minecraft/prune/HeuristicService.java) | Size and entity-aware heuristic filtering. | Two modes: `size` (threshold-based), `entity-aware` (payload scanning). Saves metadata to PlanStore. |
| [PlanStore.java](src/main/java/dev/minecraft/prune/PlanStore.java) | Artifact metadata persistence (CSV index + per-plan metadata). | Keeps all plans queryable. Index lives at `<reports>/plans.index`. |
| [ClaimBoundsProvider.java](src/main/java/dev/minecraft/prune/ClaimBoundsProvider.java) | GriefPrevention integration (reflection) + claim file parsing. | Gracefully falls back if GP API unavailable or not installed. |
| [CoreProtectProvider.java](src/main/java/dev/minecraft/prune/CoreProtectProvider.java) | Queries CoreProtect's SQLite `database.db` directly — no `api.enabled` requirement. `isAvailable()` returns true when the DB file exists. `hasRecentActivity(worldName, rx, rz, days)` runs a bounded `co_block` lookup. `sqlite-jdbc` is shaded into the plugin JAR. |
| [ApplyService.java](src/main/java/dev/minecraft/prune/ApplyService.java) | Quarantine-only region moves. Lock file, apply-manifest.json, idempotent re-run, stale lock detection/recovery.
| [RestoreService.java](src/main/java/dev/minecraft/prune/RestoreService.java) | Manifest-guided restore. Optional `applyId` arg or latest. Renames manifest to `.restored.json` on completion.
| [PurgeService.java](src/main/java/dev/minecraft/prune/PurgeService.java) | Permanent deletion of quarantine dirs. Lists ACTIVE/RESTORED/INCOMPLETE dirs. Token derived via `tokenForApply()`.
| [ScheduleService.java](src/main/java/dev/minecraft/prune/ScheduleService.java) | Automated hourly heartbeat. Reads `schedule.enabled/intervalHours/worlds` from config. Generates a combined plan then immediately applies it (quarantine-only). Persists last-run timestamps to `schedule-state.properties`. Stops gracefully on `onDisable`.
| [PruneMapRenderer.java](src/main/java/dev/minecraft/prune/PruneMapRenderer.java) | `MapRenderer` subclass that paints keep/prune/zone region squares onto a 128×128 Minecraft map canvas. Green=keep, red=prune, blue=zone. Bottom 10px reserved for coloured legend swatches. |
| [MapVisualizer.java](src/main/java/dev/minecraft/prune/MapVisualizer.java) | Reads plan region files, builds a `MapView` with `PruneMapRenderer`, and returns a `FILLED_MAP` `ItemStack` with display name and lore. Called by `/prune map`. |
| [JsonUtil.java](src/main/java/dev/minecraft/prune/JsonUtil.java) | Minimal JSON serialiser (no external deps). `toJson(Map<String,Object>)` handles String, Number, Boolean, List<String>, null. Used by PlanService, HeuristicService, ApplyService for `summary.json` / `apply-report.json`. |
| [PruneCommand.java](src/main/java/dev/minecraft/prune/PruneCommand.java) | Command parsing and dispatch. Async planning via Bukkit scheduler.
| [WorldPrunePlugin.java](src/main/java/dev/minecraft/prune/WorldPrunePlugin.java) | Plugin bootstrap. Initializes all services, registers command, warns on stale locks at startup.
| [build.gradle.kts](build.gradle.kts) | Gradle build config — Java 21 + Spigot API 1.21.1 (runs on Spigot, Paper, and all forks). Shadow plugin (`com.gradleup.shadow`) shades `sqlite-jdbc` into the output JAR. |
| [config.yml](src/main/resources/config.yml) | Runtime defaults. | Claims path, keepRules thresholds, margin chunks. |
| [modrinth-description.md](modrinth-description.md) | Modrinth project description. Automatically pushed to Modrinth on every release via `PATCH /v2/project/worldprune` in `modrinth.yml`. Edit this file to update what players see on the Modrinth project page. |

## Conventional Commits

All commit messages **must** follow the Conventional Commits spec. semantic-release reads these to determine the version bump and generate the changelog automatically.

| Prefix | Version bump | When to use |
|--------|-------------|-------------|
| `feat:` | **minor** (0.x.0) | New user-visible feature |
| `fix:` | **patch** (0.0.x) | Bug fix |
| `perf:` | **patch** | Performance improvement |
| `refactor:`, `chore:`, `ci:`, `docs:`, `test:`, `style:` | none | Internal changes |
| `feat!:` or `BREAKING CHANGE:` footer | **major** (x.0.0) | Breaking API/config change |

Examples:
```
feat: add /prune schedule list command
fix: seed files as minecraft user to avoid AccessDeniedException
chore: add Checkstyle + SpotBugs; fix all lint violations
ci: replace 4-step integration sequence with single ./gradlew integration
```

Scope is optional but encouraged: `feat(heuristic): add chest-minecart exclusion`.

The `commit-msg` hook validates format locally. Install once: `./gradlew installHooks`.

## Development Workflow

1. Run `./gradlew build` before and after changes to verify zero errors.
2. If adding config keys, add defaults to `config.yml`.
3. Always call `savePlanMetadata()` after generating a plan so it appears in `/prune plans`.
4. Commit messages must follow Conventional Commits (see above) — this drives the version bump.

### Async Operations
- All long-running tasks (planning, heuristic scan, apply/undo) run via `Bukkit.getScheduler().runTaskAsynchronously(plugin, ...)`.
- Command sender receives feedback immediately; work completes in background.
- Use `sender.sendMessage()` for progress updates.

### Error Handling
- Catch exceptions in async tasks and log to `plugin.getLogger()`.
- Always return something to the operator (success or error message).
- Never crash the server; fail gracefully.

## Release Process

Releases are **fully automated** via [semantic-release](https://semantic-release.gitbook.io/) on every push to `main`.

### How it works
1. Commits merged to `main` are analysed against Conventional Commits.
2. If any `feat:` or `fix:` (or higher) commits are present, semantic-release:
   - Determines the next version (patch / minor / major).
   - Updates `version = "X.Y.Z"` in `build.gradle.kts`.
   - Generates / prepends `CHANGELOG.md`.
   - Builds the shadow JAR (`./gradlew shadowJar`).
   - Commits those changes back to `main` with `[skip ci]`.
   - Creates a GitHub Release with the JAR attached.
3. The GitHub Release `published` event triggers `.github/workflows/modrinth.yml`, which:
   - Publishes the JAR to Modrinth using `Kir-Antipov/mc-publish`.
   - Derives the supported game versions automatically from the `spigot-version` matrix in `ci.yml` — no manual list to maintain.
   - Updates the Modrinth project description from [`modrinth-description.md`](modrinth-description.md).

### Updating the Modrinth project description
Edit [`modrinth-description.md`](modrinth-description.md) and commit the change. The next release will push the updated description to Modrinth automatically via `PATCH /v2/project/worldprune`.

### Required GitHub configuration (one-time, repo settings)
| Type | Name | Value |
|------|------|-------|
| Secret | `MODRINTH_TOKEN` | Modrinth API token with *Create versions* and *Edit projects* scope |

### Bootstrapping the first automated release
On the first merge to `main` after adopting this workflow, create an annotated tag for the current version **before** pushing, so semantic-release has a baseline:
```bash
git tag -a v0.1.0 -m "chore(release): 0.1.0 [skip ci]"
git push origin v0.1.0
```
Semantic-release will then only consider commits **after** that tag.

### Local release tooling setup
```bash
npm install           # install semantic-release + commitlint devDependencies
./gradlew installHooks  # install pre-commit + commit-msg hooks
```

## Safety Model

- **Quarantine-first**: files are moved to `<world>/quarantine/<apply-id>/`, never hard deleted by apply.
- **Manifest**: every apply writes `apply-manifest.json` to enable full rollback via undo.
- **Lock file**: `<world>/quarantine/.apply-lock` prevents concurrent applies to the same world. Stale locks (from server crashes) are detected on startup and at apply-preview time; cleared with `--force-unlock`.
- **Staged confirm**: destructive operations (`apply`, `drop`) show a preview then require `/prune confirm` within 30 seconds. No clipboard-copy of tokens required.
- **Idempotent**: re-running the same apply ID safely skips already-quarantined files.
- **Drop**: permanent deletion uses `PurgeService.tokenForApply()` internally; the operator never needs to copy/paste a token.

## Permissions

```
prune.admin        (default: op)  — scan, plans, plan, status
  └── prune.admin.apply           — apply, confirm (apply), undo
  └── prune.admin.purge           — drop, confirm (drop), quarantine
```

## Testing

### Framework & Tooling
- **Unit test framework**: JUnit 5 (`junit-jupiter`)
- **Language**: Java tests under `src/test/java`
- **Mocking**: Mockito (`mockito-core`) for Bukkit interfaces
- **Coverage**: JaCoCo HTML + XML report via `jacocoTestReport`
- **Build command**: `./gradlew test` (or `./gradlew build`)

### Current automated test coverage
- [src/test/java/dev/minecraft/prune/PlanStoreTest.java](src/test/java/dev/minecraft/prune/PlanStoreTest.java)
  - Metadata persistence round-trip (`savePlanMetadata()` / `loadPlanMetadata()`)
  - Index ordering and world filtering (`listPlans()`)
  - Malformed index-line tolerance
- [src/test/java/dev/minecraft/prune/ClaimBoundsProviderTest.java](src/test/java/dev/minecraft/prune/ClaimBoundsProviderTest.java)
  - File-based claim parsing fallback path (GriefPrevention)
  - World marker filtering behavior
  - Missing claim-directory behavior
  - Towny file fallback: chunk-coord `.data` file parsing, world-name case-insensitivity, missing directory
  - Residence file fallback: `Global.yml` parsing, world filtering, sub-zone areas
  - Multi-source merge: GP + Towny combine into single `ClaimLoadResult`
- [src/test/java/dev/minecraft/prune/HeuristicModeTest.java](src/test/java/dev/minecraft/prune/HeuristicModeTest.java)
  - CLI parsing stability and safe defaults
- [src/test/java/dev/minecraft/prune/HeuristicServiceTest.java](src/test/java/dev/minecraft/prune/HeuristicServiceTest.java)
  - Entity-aware evaluation: missing/empty dir, non-.mca files ignored
  - Size fast-path: file ≥ threshold → kept without scanning
  - Payload scan: villager, item_frame, armor_stand (GZIP-compressed), customname, owneruuid, owner signals → keep
  - Payload scan: no signals, file shorter than 8192-byte header → prune
  - `minecraft:chest_minecart` explicitly excluded even when in strongEntityIds
  - Corrupt/undecompressable chunk → conservative keep
  - Mixed multi-file result; case-insensitive payload matching
  - `applyCoreProtectRescue`: no provider, unavailable provider, activity detected, no activity, un-parseable filename, already-kept region not doubled
- [src/test/java/dev/minecraft/prune/RectTest.java](src/test/java/dev/minecraft/prune/RectTest.java)
  - Coordinate normalization helpers
- [src/test/java/dev/minecraft/prune/JsonUtilTest.java](src/test/java/dev/minecraft/prune/JsonUtilTest.java)
  - String, number, boolean, null, and List&lt;String&gt; value encoding
  - String escaping (quotes, backslash, newline, tab)
  - Insertion-order preservation; comma placement; multi-field object structure
- [src/test/java/dev/minecraft/prune/ApplyServiceTest.java](src/test/java/dev/minecraft/prune/ApplyServiceTest.java)
  - `loadCandidates()` file resolution (canonical + heuristic variant names, non-.mca filtering)
  - Full apply round-trip: file moves, quarantine structure, manifest content, applyId format
  - Lock file: created during apply, cleaned up after, error on duplicate
  - Idempotency: second apply with files already quarantined skips gracefully
  - Wrong token: throws IOException
  - No candidates: completes gracefully with progress message
- [src/test/java/dev/minecraft/prune/RestoreServiceTest.java](src/test/java/dev/minecraft/prune/RestoreServiceTest.java)
  - `readManifestMoves()`: normal manifest, empty array, missing key
  - `findLatestApplyDir()`: max-by-name selection, ignores dirs without manifest, null when empty/missing
  - Full restore round-trip: files returned, manifest renamed to `.restored.json`
  - Latest apply (null applyId) resolves correctly
  - Missing manifest / missing apply dir: throws IOException
  - Idempotency: skips gracefully when destination already exists

- [src/test/java/dev/minecraft/prune/PruneCommandTest.java](src/test/java/dev/minecraft/prune/PruneCommandTest.java)
  - Permission gating (no `prune.admin` → rejected; no `prune.admin.apply` → apply rejected)
  - Subcommand routing: `noArgs` → usage; unknown subcommand → error
  - `status` — banner, source, and keep-rules mode fields
  - `plans` — empty state message
  - `plan <id>` — missing arg error; unknown ID error
  - `confirm` with nothing staged
- [src/test/java/dev/minecraft/prune/MapVisualizerParseTest.java](src/test/java/dev/minecraft/prune/MapVisualizerParseTest.java)
  - `parseRegionName()`: normal positive/negative coords, zero-zero, wrong extension, missing `r` prefix, too few/many parts, non-numeric coord, null input
- [src/test/java/dev/minecraft/prune/CoreProtectProviderTest.java](src/test/java/dev/minecraft/prune/CoreProtectProviderTest.java)
  - Unavailable when DB file does not exist
  - Available when DB file exists
  - Stub constructors (available/unavailable) for behaviour overrides
  - `hasRecentActivity` returns false when unavailable
  - Detects activity within region bounds and lookback window (real SQLite)
  - Returns false for activity in a different region
  - Returns false for activity in a different world
  - Returns false for activity outside the lookback window
  - Returns false (fail-open) on corrupt/empty DB file
  - Behaviour-override subclass pattern verified

**Total: 133 automated tests passing.**

### Integration Testing

Integration tests run against a live Docker container via `rcon-cli`. They run automatically in CI (`.github/workflows/ci.yml`); for local runs, Java is managed by **mise** and all tasks are Gradle tasks.

**Prerequisites** (one-time, local only — not needed in CI):
```bash
brew install mise
```

**Container**: `paper-test-server` is managed via `docker-compose.yml` at the repo root.  
`docker-compose.yml` declares `MODRINTH_PROJECTS: coreprotect` — itzg downloads CoreProtect from Modrinth automatically on first container start, so no manual CP deployment is needed.  

Configurable env vars: `MINECRAFT_CONTAINER`, `MINECRAFT_WORLD`, `MINECRAFT_DATA_DIR` (data volume path, defaults to `$HOME/minecraft-test-server/data`).

**Gradle tasks:**

| Command | What it does |
|---|---|
| `./gradlew build` | Compile + unit tests |
| `./gradlew serverStart` | Start stopped container or create it fresh (polls RCON, up to 5 min) |
| `./gradlew deploy` | Build then copy JAR into container |
| `./gradlew serverReload` | `reload confirm` via rcon |
| `./gradlew seed` | Create all fixture `.mca` files + seed CoreProtect DB |
| `./gradlew integrationTest` | Run `integration/run.sh` against an already-running container |
| `./gradlew integration` | Full suite: serverStart → deploy → serverReload → seed → integrationTest → serverStop |
| `./gradlew serverStop` | Destroy the container (`docker compose down`) |
| `./gradlew rcon -Pargs="prune status"` | One-off rcon command |
| `./gradlew logs` | Tail container logs |

```bash
# Full flow (creates/starts container if needed, runs all tests, stops container):
./gradlew integration

# Run tests against an already-running container (no rebuild):
MINECRAFT_CONTAINER=my-server MINECRAFT_WORLD=survival ./gradlew integrationTest
```

**Test script** — `integration/run.sh` covers 77 assertions (47 standard + 18 CoreProtect + 12 Towny/Residence):
- `status`, `scan`, `plans`, `plan <id>`
- `apply` preview + staged `confirm`
- quarantine listing (ACTIVE), `undo` (RESTORED), `drop` + confirm + gone
- `confirm` with nothing pending; unknown subcommand; missing arg
- CoreProtect: status shows active, scan rescues r.0.0, prunes r.1.0
- `summary.json` contains non-zero `coreprotectRescued`

**Seed script** — `integration/seed.sh` creates all fixtures in one pass:
- `r.100.100.mca`, `r.101.100.mca` — far-from-spawn prune candidates
- `r.0.0.mca`, `r.1.0.mca` — CoreProtect fixture regions
- Seeds CoreProtect DB with block activity at (256, 64, 256) → rescues r.0.0
- `plugins/Towny/data/townblocks/world_1600_1600.data` — Towny file-fallback fixture → keeps r.50.50
- `plugins/Residence/Save/Global.yml` with a residence at blocks (1632–1647, 1600–1615) → keeps r.51.50

### Unit Testing
- Run with `./gradlew test`.
- View coverage at `build/reports/jacoco/test/html/index.html`.
- Prefer deterministic tests (temp directories + fixed file contents + no server runtime).


## Configuration

### Default Values (config.yml)
```yaml
storage:
  dataRoot: ""  # Empty = use plugin data folder

claims:
  path: "plugins/GriefPreventionData/ClaimData"

manualKeep:
  path: ""  # Optional

plan:
  claimMarginChunks: 5

keepRules:
  mode: "entity-aware"
  entitySizeKeepBytes: 262144
  strongEntityIds:
    - "minecraft:item_frame"
    - "minecraft:glow_item_frame"
    - "minecraft:armor_stand"
    - "minecraft:painting"
    - "minecraft:leash_knot"
  size:
    entitiesMinBytes: 131072
    poiMinBytes: 65536
    regionMinBytes: 65536

safety:
  dryRunDefault: true

# WARNING: enabling this automates the full scan+apply cycle unattended
schedule:
  enabled: false
  intervalHours: 168
  autoPurge:
    enabled: false   # WARNING: permanently deletes quarantine entries older than retainDays
    retainDays: 30
  worlds:
    - world
```

## Common Pitfalls

1. **Async scheduling**: Always use `Bukkit.getScheduler().runTaskAsynchronously()` for I/O.
2. **GriefPrevention reflection**: Gracefully handle missing API; don't crash.
3. **Chunk → Region math**: Regions are 32×32 chunks. Use `Math.floorDiv(chunk, 32)` for region ID.
4. **File paths**: Always resolve relative paths relative to world container or plugin data folder.
5. **PlanStore**: Always call `savePlanMetadata()` after generating a plan so it's discoverable via `plan list`.

## Debugging

- Check server logs for `[WorldPrune]` prefix.
- Inspect report files directly: `ls plugins/WorldPrune/reports/plan-*/`.
- Query PlanStore index: `cat plugins/WorldPrune/reports/plans.index`.
- Enable debug logging in Paper server config if needed.

## References

- **GriefPrevention API**: Use reflection to avoid hard dependency.
- **Spigot/Bukkit API docs**: https://hub.spigotmc.org/javadocs/spigot/
- **Paper API docs**: https://papermc.io/javadocs/paper/1.21.1/ (superset; safe to reference for Paper-specific behaviour)
- **Minecraft region format**: Each region file covers 32×32 chunks (512×512 blocks).


