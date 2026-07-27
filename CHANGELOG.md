# Changelog

All notable changes to AndroidViewModel will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this
project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.1] - 2026-07-27

### Fixed

- Require an explicit key for every `aliveForever` spec, including root
  bindings as well as ViewModel-to-ViewModel dependencies. Invalid
  configurations now throw `ViewModelError` before the builder runs, while the
  Store enforces the same rule for internal factories.

### Tests

- Add root, nested, computed-key, and Store-boundary validation coverage while
  retaining single-fork, sequential unit-test execution.

## [0.2.0] - 2026-07-27

Aligns AndroidViewModel's module-composition, dependency ownership, and
instance-identity semantics with Flutter `view_model`.

### Changed

- Give every parent object generation a stable dependency binding that owns
  resolved children and mirrors current root owners in real time.
- Reuse one unkeyed ViewModel per resolved type within a binding while keeping
  different bindings isolated.
- Track direct and parent-propagated ownership paths independently with
  source-aware references.
- Deduplicate synchronous dependency propagation per binding, including
  diamond graphs.
- Make `recycle` global and preserve owners plus binding-owned subscriptions
  during `recreate`.

### Safety

- Reject nested unkeyed `aliveForever` dependencies.
- Detect recursive construction and runtime dependency cycles.
- Roll back children created by failed builders and reject reset-invalidated
  recreation replacements atomically.

### Documentation

- Align README, project guidance, and the bundled skill with spec-first
  resolution, managed non-singleton modules, resolver properties, advanced
  cached lookup, and source-aware lifecycle terminology.

### Tests

- Add coverage for stable unkeyed identity, shared-parent handoff, independent
  ownership paths, global child recycle, watch/read bubbling, diamond
  de-duplication, recreation, construction rollback, cycle detection, and
  reset-during-recreate behavior.
- Force unit tests to one JVM fork and require sequential runner execution.

## [0.1.1] - 2026-06-07

### Added

- Add JitPack publishing and Git source dependency documentation.

## [0.1.0] - 2026-06-06

### Added

- Initial AndroidViewModel release.
