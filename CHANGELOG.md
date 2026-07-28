# Changelog

All notable changes to AndroidViewModel will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this
project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Add an English, skill-local Instagram architecture example showing a
  multi-root Compose app composed from API, repository, user, feed,
  post-detail, comment, and startup-coordinator ViewModels.

## [0.3.0] - 2026-07-27

### Removed

- Remove the public `ViewModelBinding.recreate` API together with Manager,
  Store, and `InstanceHandle` in-place replacement logic, plus all
  `InstanceAction` / `currentAction` bookkeeping.
- Remove the Compose `ViewModelBuilder` convenience function. Use
  `watchViewModel(spec)` for reactive spec-based resolution.

### Changed

- AutoDispose now observes handle disposal only. Owner paths, ViewModel
  listeners, binding-owned subscriptions, and dependency edges are no longer
  migrated between object generations.
- Obtain a distinct instance with a new explicit key, or globally `recycle` the
  old generation and let a resolver property call `watch/read(spec)` again.

## [0.2.2] - 2026-07-27

### Documentation

- Make stable specs plus `watch/read` the unambiguous default in the README,
  bundled skill, project guidance, examples, and public API documentation.
- Separate cached lookup into an advanced-only section and document its cache
  miss, creation-order, identity, and cross-owner lifecycle coupling.
- Replace stored ViewModel references in the sample with resolver properties
  and remove unnecessary `aliveForever` retention.

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
