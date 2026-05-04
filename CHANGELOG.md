# [0.3.0-beta.3](https://github.com/dave-i-am/WorldPrune/compare/v0.3.0-beta.2...v0.3.0-beta.3) (2026-05-04)


### Features

* add /prune scan all and web-map async safety ([f428b5f](https://github.com/dave-i-am/WorldPrune/commit/f428b5f2a00c326714c20673440c279e77b73bb3))
* **claims:** add WorldGuard region support ([856300d](https://github.com/dave-i-am/WorldPrune/commit/856300def67bcbc96cf512a8b64876daeacf8e73))
* **map:** add BlueMap and Dynmap web-map overlay ([6aadba9](https://github.com/dave-i-am/WorldPrune/commit/6aadba970bbd60d7d0f1370004799a7165dce97a))

# [0.3.0-beta.2](https://github.com/dave-i-am/WorldPrune/compare/v0.3.0-beta.1...v0.3.0-beta.2) (2026-05-02)


### Features

* add Towny Advanced and Residence claim support ([542a308](https://github.com/dave-i-am/WorldPrune/commit/542a3084d5dec001b409e7c9ff98fd19871a5219))

# [0.3.0](https://github.com/dave-i-am/WorldPrune/compare/v0.2.1...v0.3.0) (2026-04-28)


### Bug Fixes

* improve tab completion coverage across all subcommands ([e76131d](https://github.com/dave-i-am/WorldPrune/commit/e76131d97c3a3d2eb1152a1e856a86410cda3976))


### Features

* add /prune schedule command showing last/next run times per world ([95fa12e](https://github.com/dave-i-am/WorldPrune/commit/95fa12e8540c4b9d07ae40887ae68b93da00ea1f))
* show disk size in /prune quarantine and /prune drop output ([4da7d6a](https://github.com/dave-i-am/WorldPrune/commit/4da7d6a4deb3763cf026d08f0e51c634f44e7305))

# [0.3.0-beta.1](https://github.com/dave-i-am/WorldPrune/compare/v0.2.1...v0.3.0-beta.1) (2026-04-12)


### Bug Fixes

* improve tab completion coverage across all subcommands ([7eeb9eb](https://github.com/dave-i-am/WorldPrune/commit/7eeb9eba16eb6797f470c494311ec37582e3dd1f))


### Features

* add /prune schedule command showing last/next run times per world ([e6707af](https://github.com/dave-i-am/WorldPrune/commit/e6707af1117a88343daf2b49659d9de2d8b1622e))
* show disk size in /prune quarantine and /prune drop output ([fafe768](https://github.com/dave-i-am/WorldPrune/commit/fafe76884ae0fa244745f274327a8a381488b88c))

## [0.2.1](https://github.com/dave-i-am/WorldPrune/compare/v0.2.0...v0.2.1) (2026-04-11)


### Performance Improvements

* strip unused sqlite-jdbc native libs from shaded JAR (14 MB -> 7 MB) ([5967d31](https://github.com/dave-i-am/WorldPrune/commit/5967d31171efae99a51532df3bc8fce7b5a68662))

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
