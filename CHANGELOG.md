# Changelog

All notable changes to AndroidViewModel will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this
project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.4.0] - 2026-08-02

### Added

- Add an English, skill-local Instagram architecture example showing a
  multi-root Compose app composed from API, repository, user, feed,
  post-detail, comment, and startup-coordinator ViewModels.
- Add nestable, idempotently restorable `overrideWith` and coroutine-isolated
  `runWithOverride` APIs to zero- through four-argument ViewModel specs.
- Add the strongly typed Compose `selectViewModelState` API with an optional
  selector-local equality rule.

### Changed

- Make `ViewModel.reset()` the complete runtime reset: it force-disposes every
  cached generation, including `aliveForever`, before clearing configuration
  and lifecycle observers.
- Align full-state and selector equality with Flutter: full state uses local,
  global, then reference identity; selected values use local, global, then
  Kotlin `==`.
- Freeze each state transition before synchronous listener dispatch so a
  reentrant `setState` cannot corrupt later listeners' previous/current pair.
- Make Compose `watchViewModel` and `readViewModel` re-resolve after their
  handle is recycled instead of retaining the disposed generation.
- Make `maybe*Cached` return `null` only for `ViewModelError`; unrelated
  exceptions now propagate to the caller.

### Fixed

- Skip listeners removed before their turn during the current notification.
- Contain failures thrown by the configured error handler so later listeners
  and disposal callbacks still run.
- Preserve explicit `null` key/tag and `false` retention values from an active
  legacy or scoped spec proxy instead of falling back to the base spec.
- Guard the complete reset sequence against teardown-triggered nested resets so
  remaining generations still use the outer error and lifecycle pipeline.
- Throw `ViewModelError` consistently when resolving through a disposed
  binding.
- Keep read-style Compose resolution and typed selectors subscribed only to
  generation changes, avoiding recomposition from unrelated watched
  ViewModels on the same binding.

### Tests

- Add serial contract coverage for reset, maybe lookup errors, listener
  mutation, reentrant state, equality fallback, secondary error handling, and
  scoped spec overrides, plus Compose recycle and typed-selector behavior.

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
