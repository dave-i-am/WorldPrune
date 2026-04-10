# Changelog

## [0.2.0] - 2026-04-11

### Added
- **CoreProtect integration** — regions with recent player activity are automatically rescued from pruning. Requires no configuration; WorldPrune detects CoreProtect by the presence of its `database.db` file.
- `/prune status` now reports `CoreProtect: active` or `CoreProtect: inactive`.
- `summary.json` includes a `coreprotectRescued` count when CoreProtect rescues regions.
- New config key `coreprotect.activityLookbackDays` (default: 180) controls the lookback window.
- `sqlite-jdbc` is bundled in the plugin JAR — no additional dependencies required.

### Changed
- CoreProtect is now a soft dependency (`softdepend` in `plugin.yml`). The plugin loads and works fully without CoreProtect installed.

## [0.1.0] - 2025-12-01

Initial release.

- Claims-based region planning via GriefPrevention (API + file parsing fallback)
- Heuristic filtering: `size` and `entity-aware` modes
- Quarantine-first apply (regions moved, never immediately deleted)
- Full rollback via `/prune undo`
- Permanent deletion via `/prune drop` with staged confirmation
- Map visualiser: `/prune map` gives a filled map showing keep/prune grid
- Scheduled automation via `schedule.enabled` config
- All destructive operations require `/prune confirm` within 30 seconds
