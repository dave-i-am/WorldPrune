# WorldPrune

Prunes inactive Minecraft region files — safely, with quarantine and rollback.

WorldPrune is an operator-only Paper/Spigot plugin that identifies region files not covered by GriefPrevention claims and moves them to a quarantine folder. Every operation is reversible. Nothing is permanently deleted until you explicitly ask for it.

## Requirements

- Paper or Spigot 1.20.1+
- Java 21+
- [GriefPrevention](https://www.spigotmc.org/resources/griefprevention.1573/) (optional — used as the primary claim source; falls back to claim file parsing if the API is unavailable)

## Installation

Drop `world-prune-plugin-0.1.0.jar` into your `plugins/` folder and restart. A default `config.yml` will be created under `plugins/WorldPrune/`.

## Commands

| Command | Description |
|---------|-------------|
| `/prune scan [world]` | Analyse a world and generate a prune plan |
| `/prune plans [world]` | List stored plans |
| `/prune plan <planId>` | Show plan details |
| `/prune apply [world] [--force-unlock]` | Preview an apply and stage for confirmation |
| `/prune confirm` | Execute the staged operation (30-second window) |
| `/prune undo [world] [<apply-id>]` | Restore files from quarantine |
| `/prune quarantine [world]` | List quarantine directories and their state |
| `/prune drop <world> <apply-id>` | Permanently delete a quarantine entry (staged) |
| `/prune map [world] [planId]` | Receive a filled map item showing the keep/prune grid |
| `/prune status` | Show plugin status and active configuration |

## How it works

1. **Scan** — WorldPrune reads your GriefPrevention claims (plus any manual keep rectangles) and builds a set of region coordinates to keep. Unclaimed regions are marked as prune candidates, subject to heuristic filtering.
2. **Heuristic filtering** — In `entity-aware` mode, each candidate's entity file is scanned for player-placed entities (item frames, paintings, armor stands, etc.). Regions containing these are kept even if unclaimed.
3. **Apply** — Candidate region files are moved to `<world>/quarantine/<apply-id>/`. An `apply-manifest.json` records every move for rollback.
4. **Undo** — `/prune undo` reads the manifest and moves files back to their original locations.
5. **Drop** — Once you're satisfied, `/prune drop` permanently deletes a quarantine entry. This cannot be undone.

## Safety model

- **Quarantine-first**: `apply` only ever moves files — it never deletes them.
- **Staged confirm**: destructive operations (`apply`, `drop`) show a preview and require `/prune confirm` within 30 seconds.
- **Lock file**: prevents concurrent applies to the same world. Stale locks from crashes can be cleared with `--force-unlock`.
- **Idempotent**: re-running the same apply skips already-quarantined files safely.

## Permissions

```
prune.admin         (default: op)  — scan, plans, plan, map, status
  └── prune.admin.apply            — apply, confirm (apply), undo
  └── prune.admin.purge            — drop, confirm (drop), quarantine
```

## Configuration

Key settings in `plugins/WorldPrune/config.yml`:

```yaml
source: combined          # claims | keepRules | combined

claims:
  path: "plugins/GriefPreventionData/ClaimData"
  marginChunks: 5         # buffer chunks around each claim

keepRules:
  mode: entity-aware      # entity-aware | size
  strongEntityIds:        # player-placed entity types that force a keep
    - "minecraft:item_frame"
    - "minecraft:glow_item_frame"
    - "minecraft:armor_stand"
    - "minecraft:painting"
    - "minecraft:leash_knot"

schedule:
  enabled: false          # WARNING: automates scan+apply unattended
  intervalHours: 168
```

## Building from source

```bash
./gradlew build
# Output: build/libs/world-prune-plugin-0.1.0.jar
```

## License

MIT
