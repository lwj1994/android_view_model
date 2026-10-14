# Changelog

All notable changes to AndroidViewModel will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this
project follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.8.2] - 2026-10-14

### Added

- Generate Kover XML and HTML coverage reports for debug unit tests, enforce
  100% production line coverage, and upload coverage to Codecov from the serial
  Android checks workflow.
- Cover Android host lifetimes with Robolectric and extend contract tests for
  Compose ownership, spec arities, legacy JVM defaults, error isolation, storage,
  process-state ordering, and reentrant pause subscriptions.

### Changed

- Align ownership commits with Flutter's runtime: validate dependency edges and
  install tracking before committing handle sources and ViewModel refs through
  one write path. Lifecycle callbacks observe committed ownership; owner lists
  are derived from sources and skip snapshots superseded by reentrant changes.
- Allow live bindings to resolve reentrantly during `onUnbind`. Remove old
  subscriptions first and keep replacement registrations and dependency edges
  independent of the cleanup still in progress.

### Fixed

- Track pending final unbinds by release identity so reentrant child callbacks
  cannot duplicate or suppress parent `onUnbind` events. Preserve callbacks before
  forced disposal and allow reacquisition before automatic disposal begins.
- Roll back failed attachments without removing shared owners or subscriptions;
  dispose unowned tentative generations even when `aliveForever` was requested.
- Preflight all dependency edges in tag batches and roll back only newly acquired
  registrations if a later attachment fails. Do not treat a key attachment error
  as a cache miss eligible for tag fallback.
- Make recursive recycle idempotent, reject ownership of recycling generations,
  and remove old cache entries before propagated owner cleanup and host refreshes.

## [0.8.1] - 2026-10-13

### Fixed

- Install the pinned SDKMAN `21.0.2-open` JDK in JitPack's `before_install`
  phase instead of relying on its `openjdk21` alias. Set `JAVA_HOME` explicitly
  for the serial Maven-local publication command, and trust only the build
  checkout for subsequent Git operations in the container.

## [0.8.0] - 2026-10-11

### Changed

- Align repeated spec resolution with Flutter `view_model` PR #82. Each binding
  caches handles by resolved type and effective key, reusing live handles it
  still owns directly without repeating Store lookup and ownership registration.
  Watch subscriptions and `aliveForever` key validation remain active on cache
  hits; unbind, recycle, handle disposal, binding disposal, and reset invalidate
  cached resolutions.
- Find the newest cached instance with a single O(n) scan. No-key/no-tag queries
  remain supported without warnings and use creation order, not access order.
  Ambiguous single-result tag queries emit debug warnings once per type and tag
  object identity without calling the tag's `hashCode`; tag matching still uses
  equality, and reset clears warning history.
- **Breaking (callback types):** Change `listenState` and `listenStateSelect`
  previous-value parameters from `T?` to `T` throughout the state and binding
  APIs. Every callback represents an actual transition; values remain nullable
  when the state or selected type itself is nullable.

### Added

- Expose `binding.unbind(vm)` to release only the current binding's direct
  ownership and subscriptions. Other owner paths remain intact, and the next
  delegated access restores ownership or resolves a new generation.

### Fixed

- Deliver reentrant state transitions in FIFO order within one update
  transaction, keeping selector values current and stopping queued callbacks
  after disposal.
- Coalesce external root refreshes only. Forward every dependency notification
  so explicit business listeners reach the final value through diamond graphs.
- Preserve generic type arguments throughout spec resolution, Store identity,
  cached queries, and construction checks instead of mixing instances by raw
  `KClass`. Require complete types for generic/non-static inner declarations,
  and retain the raw-type checks' required metadata in R8 consumer rules.
- Roll back constructor-registered cleanup and cancel the failed generation's
  coroutine scope when construction fails before a handle exists.
- Give shared pause providers independent subscriptions. External sources are
  borrowed by default; `ownsProvider = true` explicitly transfers disposal
  responsibility to the binding.
- Publish local corrections made during remote-state delivery, freeze record
  versions at state commit, and skip pending snapshots superseded by newer state.
- Keep remote-state collection alive after comparator failures and preserve
  coroutine cancellation. Route failures while creating an observation Flow
  through the same error pipeline as failures from the Flow itself.
- Release root owner paths propagated through a dependency edge when its parent
  binding unbinds the child, while preserving independent owner paths.
- Defer parent update notifications until unbind detachment is complete so
  callback-driven resolution can safely acquire the current generation.
- Reject reentrant ownership acquisition through the binding being unbound,
  including cached and tag-batch queries; `maybe*Cached` returns `null` during
  cleanup. Other bindings remain usable.
- Exclude handles already being recycled from resolution cache hits.
- Resolve the R8 metadata smoke test's tool from the applied Android Gradle
  plugin, including AGP distributions that embed R8 in `builder.jar`.
- Gate tag ambiguity diagnostics on the host-supplied `ViewModelConfig.isDebugMode`
  instead of the library's build variant, so published release AARs can warn in
  debug applications. The flag defaults to false and is independent of normal logging.

### Documentation

- Clarify that spec objects, builders, and constructor arguments are not part of
  instance identity; only the resolved type and effective key identify a VM.
  Keep stable specs and `by` delegates as the primary business APIs, and explain
  resolution caching, cached-query ordering, and listener nullability in the
  README and bundled skill.

## [0.7.2] - 2026-09-09

### Changed

- **Breaking:** Rename `rememberRetainedViewModelBinding()` to
  `rememberScreenViewModelBinding()` across the API, imports, and examples.
  The old name is removed without a compatibility alias.
- Keep `rememberViewModelBinding()` and default Compose consumers local to
  composition. The screen API still shares the current ViewModelStoreOwner's
  binding and falls back to local scope when no owner exists.
- Expand API comments, README, skill, and example documentation to distinguish
  sharing, disposal, configuration changes, owner lookup, and provider behavior.

## [0.7.1] - 2026-09-09

### Added

- Add `binding.watchViewModel(spec)` and `binding.readViewModel(spec)` delegates
  for existing fixed bindings. Keep deferred binding lambdas for lifecycle-bound
  lookup, and distinguish both forms from Compose subscription APIs in the docs.
- Add README badges for the latest release, JitPack, Android checks, and license.

### Tests

- Verify fixed binding delegates preserve generation resolution after recycle,
  do not follow binding reassignment, and reject access after binding disposal.

## [0.7.0] - 2026-09-09

### Changed

- **Breaking:** Compose `watchViewModel/readViewModel` now return property
  delegates. Use `val vm by watchViewModel(spec)`; the former direct-VM return
  API is not retained.
- Hosts, plain classes, and nested VMs use the same delegate names with a deferred
  binding lambda. Each access resolves the current generation; callbacks after
  recycle do not need to wait for recomposition.
- Update the README, skill, and examples. Use callback lambdas to avoid capturing
  old VM instances through bound method references. Standardize documentation
  in English.

### Tests

- Cover deferred resolution, repeated-access deduplication, binding replacement,
  parent disposal, Compose watch/read notification differences, and old callbacks
  resolving the current generation after recycle.

## [0.6.0] - 2026-09-09

### Removed

- **Breaking:** Remove the `ViewModel.onDependencyNotify` override hook and
  internal `onDependencyUpdate` callback plumbing. Use binding `listen`,
  `listenState`, or `listenStateSelect` for business reactions, registered once
  during initialization. `watch` still forwards child notifications through the
  parent to watching bindings; `read` still does not observe child notifications.
  Ownership and handle-disposal observation are unchanged.

### Changed

- Document that `watchViewModel` invalidates its calling composable scope and
  that passing the same ViewModel reference does not transfer observation to a
  child under Compose strong skipping.
- Define resolved ViewModel instances as local to their ownership boundary.
  Pass stable specs across owners and resolve them at the consumer; composable
  boundaries accept immutable render values and event callbacks instead of VM
  instances.

### Fixed

- Remove each pause source when its lifecycle owner is destroyed, preventing
  retained bindings from keeping stale pause state after configuration changes.
- Aggregate pause sources with OR and dispatch pause/resume callbacks only when
  the aggregate changes. Controller destruction does not trigger resume.
- Reject new ownership in instance and tag-batch queries on disposed bindings,
  including reentrant queries during teardown.

### Tests

- Assert dependency forwarding through ordinary listeners instead of an override
  hook. Cover read-only root bindings, explicit business listeners, subscription
  cleanup, and the absence of subscription migration after child recycle.
- Add regression tests for lifecycle pauses, retained host cleanup, and disposed
  binding queries. Run unit tests, builds, and Lint serially in GitHub Actions.

## [0.5.1] - 2026-08-30

### Fixed

- Build JitPack releases with JDK 21 to match the configured Gradle toolchain.

## [0.5.0] - 2026-08-30

### Added

- Add optional cross-process `StateViewModel` snapshot synchronization through
  `ProcessStateStore`, versioned `ProcessStateRecord`, and the Android-specific
  `ParcelableProcessStateStore` boundary.
- Add a runnable three-process example using `MainActivity`, a `:remote`
  Activity, and a non-exported `ContentProvider` in `:state_store`.
- Document binding-scoped sharing across pages and distinguish it from Android
  cross-process state transport in the README and bundled AI skill.
- Add the bundled skill installation command near the start of the README.

### Changed

- Require `ProcessStateStore.observe()` to emit the current snapshot and every
  accepted update as one continuous stream, eliminating the race between an
  initial read and observation.
- Use JDK 21 as the Gradle toolchain while retaining Java 17 bytecode
  compatibility.

### Tests

- Cover initial process state, local writes, remote updates, echo prevention,
  version ordering, and synchronization between distinct ViewModel instances.
- Verify the example on an API 36 emulator with separate main, remote, and
  state-store process IDs.

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
