# AGENTS.md

AndroidViewModel project constraints for AI agents and automation tools.

## Overview

AndroidViewModel is the Android implementation of Flutter `view_model`'s core
model. Business capabilities are ViewModels composed through stable specs and
`by` property delegates. The binding graph manages lifecycle through
source-aware owner paths and disposes instances when their final owner path leaves.

Write project documentation in English.

## Layout

```text
android-view-model/src/main/kotlin/milu/viewmodel/  Core runtime and Android hosts
android-view-model/src/test/kotlin/milu/viewmodel/  Unit and lifecycle contract tests
example/                                          Host usage examples
skills/android-view-model/                        AI skill
```

## Core invariants

1. Stable specs with `by watchViewModel/readViewModel` are the primary business
   APIs. Delegates use binding `watch/read` to create/retrieve, bind, and observe
   handle disposal; only `watch` observes VM notifications. Keep passing the spec
   even with a key/tag. Cached APIs only query instances created by other paths;
   they are advanced escape hatches, not equally recommended entry points.
   Preserve this priority in the README, skill, examples, and public API comments.
2. Identity is the resolved ViewModel type plus effective key. Unkeyed instances
   are reused by type within one binding and isolated across bindings. Explicit
   keys are for cross-binding sharing or multiple instances of the same type.
3. Default to binding-managed non-singleton modules. Do not automatically add
   keys or `aliveForever` to ordinary services. Every `aliveForever` spec requires
   an explicit key, validated before the builder for both root and nested
   resolution, with a Store-level safeguard. `recycle/ViewModel.reset` can still
   force disposal.
4. Each parent generation lazily owns one stable dependency binding. It keeps
   resolved children alive and propagates root owners in real time. Direct and
   multiple parent paths release ownership independently by source.
5. Business VM properties use `by watchViewModel/readViewModel(spec)`. Outside
   Compose, use `binding.watchViewModel/readViewModel(spec)` for an existing fixed
   binding, or `{ viewModelBinding }` for deferred/changing bindings. Both resolve
   through `watch/read(spec)` on every access. Keep deferred lookup for early host
   initialization and lazy nested dependency bindings. Compose UI access uses
   top-level composable functions, even with an explicit binding; receiver
   extensions do not establish recomposition subscriptions.
   Do not cache instances with `by lazy` or stored references. Compose subscribes
   during composition; delegates do not cache VMs. Event callbacks use
   `{ vm.action() }`, not `vm::action`, which captures an instance immediately.
   Do not pass delegates across owners.
6. Within a ViewModel, `read` does not bubble child notifications; `watch`
   automatically forwards them to the parent and ultimately its watching
   bindings. Their lifecycle semantics match, but notification semantics differ.
   There is no dependency-update business hook. Register binding
   `listen/listenState/listenStateSelect` once during initialization, never in a
   delegate's binding lambda. Synchronous graphs deduplicate updates by binding;
   both `read/watch` still observe handle disposal.
7. Do not introduce an in-place `recreate` API. Use a new explicit key for an
   independent instance. If affecting all owners is intentional, globally
   `recycle` first; the next delegated access uses the normal `watch/read(spec)`
   cache-miss path to create a new handle and dependency tree. Do not migrate
   relationships from the old object.
8. All public ViewModel APIs are main-thread only. Business ViewModels do not
   extend AndroidX `ViewModel`; AndroidX is only for host retention.
9. `ViewModel.reset()` is the complete process-wide test reset: force-dispose all
   cached generations before clearing config and lifecycle. Guard the entire
   sequence against reentrancy so a nested reset during disposal cannot clear
   the outer operation's error/lifecycle pipeline prematurely. Do not require
   callers to reset InstanceManager separately.
10. Scoped spec overrides preserve nesting, idempotent/out-of-order restoration,
    and coroutine isolation. An active proxy's null key/tag and false
    aliveForever are complete overrides, not fallbacks to the base spec.
11. State equality is local → global → identity; selector equality is local →
    global → Kotlin `==`. Compose typed selectors use read-style ownership.
    Watch/read must resolve the current generation after recycle.
12. Resolved `ViewModel` / `StateViewModel` instances stay within the ownership
    boundary that resolved them. Never pass them as dependencies or arguments
    across components, layers, hosts, bindings, or owners. Pass stable specs;
    each consumer resolves through a `by` delegate using its own binding to
    establish ownership and retrieve new generations after recycle. Composable
    boundaries accept immutable render values and event callbacks, not VMs.
    `watchViewModel` observation does not travel with a shared VM reference.

## Test rules

- Run tests on one thread, in one JVM fork, in runner order. No parallel forks,
  sharding, or concurrent runners: registry, config, lifecycle, reset, and spec
  proxy state are process-wide.
- Keep `maxParallelForks = 1` in `android-view-model/build.gradle.kts`.
- Put ViewModel constructors inside `viewModelSpec` builders. Do not directly
  construct managed ViewModels in test bodies or `setUp()`.
- Do not store resolved ViewModels in test fields. Use
  `by readViewModel(spec) { binding }` properties.
- Dispose every binding and call the complete `ViewModel.reset()` between cases.

## Verification

```bash
./gradlew :android-view-model:testDebugUnitTest \
  :android-view-model:assembleDebug \
  :android-view-model:lintDebug \
  --no-parallel --max-workers=1
```

Do not add `--parallel`.

## Release process

JitPack tags are the recommended distribution method:

1. Update the version in `android-view-model/build.gradle.kts` and README
   installation examples.
2. Update `CHANGELOG.md` and run the serial tests, build, and Lint above.
3. Commit and push `main`.
4. Create an annotated tag `X.Y.Z` without a `v` prefix, push it, and create a
   GitHub Release with the same name.

Never move a pushed tag; publish a new version for corrections. Do not run Maven
Central publishing tasks unless explicitly requested and credentials are available.

## Host and pause boundaries

- Retained bindings end with their `ViewModelStore`. Each lifecycle pause source
  follows its own owner and must be removed from the controller on `ON_DESTROY`;
  disposing it alone must not leave stale pause state.
- Aggregate pause sources with OR. Call `onPause/onResume` only when the aggregate
  changes. One source resuming must not override another paused source, and
  destroying the controller must not trigger resume callbacks.
- All queries on disposed bindings must reject new ownership, including tag
  batch queries and teardown reentrancy after the binding is marked disposed
  but before controller cleanup completes.
