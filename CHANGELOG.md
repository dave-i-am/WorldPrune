# [0.2.0](https://github.com/dave-i-am/WorldPrune/compare/v0.1.0...v0.2.0) (2026-04-11)


### Bug Fixes

* use single leading color per quarantine line so RCON never truncates status word ([82c2caf](https://github.com/dave-i-am/WorldPrune/commit/82c2caf37e158161695185ad07034c91c697e450))


### Features

* CoreProtect integration via direct SQLite query ([01e0503](https://github.com/dave-i-am/WorldPrune/commit/01e050352d6d8aa21387fadb46ffd571c134e62c))


### Performance Improvements

* batch CoreProtect region activity queries into single DB connection ([c4e805f](https://github.com/dave-i-am/WorldPrune/commit/c4e805fe5fb54a55245a02b1552f44fcd2cf7d92))

# [0.2.0](https://github.com/dave-i-am/WorldPrune/compare/v0.1.0...v0.2.0) (2026-04-11)


### Bug Fixes

* **release:** pin @semantic-release/github to ensure JAR is attached to releases ([08d9624](https://github.com/dave-i-am/WorldPrune/commit/08d962415970ab640782e6349f41c538ffb1940e))
* use single leading color per quarantine line so RCON never truncates status word ([82c2caf](https://github.com/dave-i-am/WorldPrune/commit/82c2caf37e158161695185ad07034c91c697e450))


### Features

* CoreProtect integration via direct SQLite query ([01e0503](https://github.com/dave-i-am/WorldPrune/commit/01e050352d6d8aa21387fadb46ffd571c134e62c))


### Performance Improvements

* batch CoreProtect region activity queries into single DB connection ([c4e805f](https://github.com/dave-i-am/WorldPrune/commit/c4e805fe5fb54a55245a02b1552f44fcd2cf7d92))

## [0.2.1](https://github.com/dave-i-am/WorldPrune/compare/v0.2.0...v0.2.1) (2026-04-11)


### Bug Fixes

* **release:** pin @semantic-release/github to ensure JAR is attached to releases ([08d9624](https://github.com/dave-i-am/WorldPrune/commit/08d962415970ab640782e6349f41c538ffb1940e))

# [0.2.0](https://github.com/dave-i-am/WorldPrune/compare/v0.1.0...v0.2.0) (2026-04-11)


### Bug Fixes

* use single leading color per quarantine line so RCON never truncates status word ([82c2caf](https://github.com/dave-i-am/WorldPrune/commit/82c2caf37e158161695185ad07034c91c697e450))


### Features

* CoreProtect integration via direct SQLite query ([01e0503](https://github.com/dave-i-am/WorldPrune/commit/01e050352d6d8aa21387fadb46ffd571c134e62c))


### Performance Improvements

* batch CoreProtect region activity queries into single DB connection ([c4e805f](https://github.com/dave-i-am/WorldPrune/commit/c4e805fe5fb54a55245a02b1552f44fcd2cf7d92))

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
