---
name: android-view-model
description: Use AndroidViewModel in Kotlin Android projects for state management, functional-module composition, dependency injection, automatic lifecycle, Compose/Activity/Fragment/View bindings, ViewModel-to-ViewModel dependencies, sharing, threading, and tests.
---

# AndroidViewModel Skill

AndroidViewModel is the Android-platform port of Flutter `view_model`'s core
model: a type-keyed registry, binding-based source-aware ownership, functional
modules composed as ViewModels, and automatic disposal. Preserve that model
while adapting host integration and threading to Android.

It intentionally does not implement Flutter/Apple `ObservableValue` APIs.

## Source of truth

- Public API and examples: [repository README](../../README.md)
- Runtime behavior: `android-view-model/src/main/kotlin/milu/viewmodel/`
- Contract tests: `android-view-model/src/test/kotlin/milu/viewmodel/`
- Architecture example: `examples/instagram_architecture/README.md` — a
  multi-file Instagram-style app composed from API, repository, user, feed,
  post-detail, comment, and startup-coordinator ViewModels.
- Conceptual upstream: Flutter `view_model` README and skill

If this skill conflicts with the repository README or tests, follow the current
repository and update the skill.

## Trigger conditions

Use this skill when:

- Code imports `milu.viewmodel.*` or uses `ViewModel`, `StateViewModel`,
  `ViewModelSpec`, `ViewModelBinding`, `watchViewModel`, or `readViewModel`.
- The task concerns state, DI, module composition, lifecycle, sharing,
  main-thread behavior, Compose/Activity/Fragment/View integration, or tests.

## Resolution decision order (must follow)

1. Keep a stable, module-level spec. Do this for Compose, Android hosts, plain
   bindings, tests, and ViewModel-to-ViewModel dependencies.
2. Resolve that spec with `watch(spec)` when ViewModel notifications should
   update the owner, or `read(spec)` when lifecycle-bound access should not
   listen to the ViewModel's own notifications. Both APIs create/reuse, bind,
   and observe handle disposal, including force-recycle.
3. Use a cached API only when the task explicitly requires an advanced
   cross-owner query of an instance already created elsewhere. Cached APIs
   cannot create a missing dependency and must not be suggested as normal DI.

A key or tag on a spec does not change this order. Pass the keyed/tagged spec to
`watch` or `read`; knowing cache identity is not a reason to bypass the spec.

## Core model

- Any functional unit can be a ViewModel: UI state, service, repository,
  coordinator, cache, controller, or domain capability.
- Prefer managed instances over global singletons. Default specs to no `key`
  and `aliveForever = false`; let the binding graph own creation and disposal.
- `milu.viewmodel.ViewModel` is the business/lifecycle base with `listen`,
  `notifyListeners`, `update`, `addDispose`, `viewModelScope`, and
  `viewModelBinding`.
- `StateViewModel<State>` adds immutable state, `setState`, `previousState`,
  `listenState`, and `listenStateSelect`.
- `ViewModelSpec<VM>` is a factory declaration, not the instance itself.
- `ViewModelBinding` is the owner/container used by Compose, Activity,
  Fragment, View, plain classes, and tests.
- Business `ViewModel` does not extend AndroidX `ViewModel`. AndroidX is used
  only to retain a binding for `ViewModelStoreOwner` hosts.
- Public ViewModel APIs are main-thread only and guarded by `@MainThread` plus
  runtime assertions.

## Identity, sharing, and retention

- Identity is the resolved ViewModel type plus the effective `key`. The
  builder's runtime result and `tag` do not participate in identity.
- With no explicit key, one binding reuses one instance per resolved ViewModel
  type and remains isolated from other bindings.
- Use a key for intentional cross-binding sharing or multiple instances of the
  same type in one binding.
- `tag` is only a grouping/lookup label.
- A key does not retain an instance.
- `aliveForever` only skips automatic disposal when ownership reaches zero.
  Explicit `recycle` and `InstanceManager.debugReset()` still dispose it.
- Every `aliveForever` spec requires an explicit key, whether it is resolved by
  a root binding or another ViewModel. Resolution throws `ViewModelError`
  before calling the builder when the key is missing or computes to `null`; the
  Store enforces the same invariant for internal factories.

```kotlin
// Managed by one resolving binding by default.
val catalogSpec = viewModelSpec { CatalogViewModel() }

// Explicit app-wide sharing and retention, only when required.
val sessionSpec = viewModelSpec(
    key = "app-session",
    aliveForever = true,
) { SessionViewModel() }
```

Parameterized factories use `viewModelSpecWithArg` and
`viewModelSpecWithArg2...4`. Prefer a key derived from arguments when equal
arguments are intended to share.

## Choosing a binding

| Context | Recommended API | Lifecycle |
| --- | --- | --- |
| Compose retained by Activity/Fragment | `rememberRetainedViewModelBinding()` | Cleared with current `ViewModelStoreOwner`. |
| Compose local composition | `rememberViewModelBinding()` | Disposed when composition leaves. |
| Compose broad rebuild | `watchViewModel(spec)` | Subscribes to VM notifications. |
| Compose access without broad rebuild | `readViewModel(spec)` | Bound, no VM-wide subscription. |
| Activity / Fragment instance | `viewModelBinding.watch/read(spec)` | Cleared with host `ViewModelStore`. |
| Fragment view lifecycle | `viewLifecycleViewModelBinding.watch/read(spec)` | Disposed with the Fragment view. |
| Activity-shared Fragment access | `activityViewModelBinding.watch/read(spec)` | Uses Activity ownership. |
| Custom View local scope | `viewModelBinding.watch/read(spec)` | Disposed on detach. |
| View tree owner | `viewTreeViewModelBinding.watch/read(spec)` | Reuses nearest owner binding. |
| Plain class / tests | `ViewModelBindingScope()` or `ViewModelBinding()` | Caller must close/dispose. |

Use resolver properties instead of `by lazy` or stored references when an
explicit global recycle can occur:

```kotlin
class MainActivity : FragmentActivity() {
    private val orders: OrdersViewModel
        get() = viewModelBinding.watch(ordersSpec)
}
```

## Primary binding APIs (recommended)

| API | Creates? | Owns on hit? | VM notifications | Handle disposal |
| --- | ---: | ---: | ---: | ---: |
| `watch(spec)` | Yes | Yes | Yes | Yes |
| `read(spec)` | Yes | Yes | No | Yes |

## Cached lookup APIs (advanced)

Do not replace a stable spec with cache lookup. These APIs couple the caller to
another path's creation order, cache identity, and lifecycle, and cannot create
a missing dependency. Show them only for an intentional query of existing
cross-owner state.

| API | Creates? | Owns on hit? | VM notifications | Handle disposal |
| --- | ---: | ---: | ---: | ---: |
| `watchCached<T>(key/tag)` | No | Yes | Yes | Yes |
| `readCached<T>(key/tag)` | No | Yes | No | Yes |
| `maybeWatchCached<T>` | No | Yes on hit | Yes | Yes |
| `maybeReadCached<T>` | No | Yes on hit | No | Yes |
| `watchCachesByTag<T>` | No, all hits | Yes | Yes | Yes |
| `readCachesByTag<T>` | No, all hits | Yes | No | Yes |

Non-`maybe` single-result cached lookups throw on a miss. A single lookup by tag
can be ambiguous and depends on cache creation order; use the batch API when a
tag may match several instances.

`listen`, `listenState`, and `listenStateSelect` are binding-owned side effects.
They resolve through `read` and are removed when the target handle or binding is
disposed. They are never migrated to another object. Never place a `listen` call
in a repeatedly evaluated resolver property.

## Response pattern for implementation requests

- Default every normal resolution example to a stable spec plus `watch(spec)`
  or `read(spec)`.
- Preserve spec-based resolution in refactors and migrations. Never introduce a
  cached API merely because a key or tag is available.
- Show cached lookup only when the user explicitly needs an already-created
  cross-owner cache entry, and state that absence, creation order, tag
  multiplicity, and the other owner's lifecycle are part of the contract.
- Default ordinary modules to an unkeyed spec with `aliveForever = false`; add a
  key or retention only when sharing or retention is intentional.

## ViewModel-to-ViewModel composition

Expose dependencies through resolver properties. Do not retain a nested
ViewModel in `by lazy`, a stored property, or an ad-hoc cache.

```kotlin
val cartSpec = viewModelSpec { CartViewModel() }
val pricingSpec = viewModelSpec { PricingViewModel() }

class CheckoutViewModel : ViewModel() {
    val cart: CartViewModel
        get() = viewModelBinding.read(cartSpec)

    val pricing: PricingViewModel
        get() = viewModelBinding.watch(pricingSpec)
}
```

- A resolver declaration creates nothing until accessed.
- Use `read` to call a child without bubbling its own notifications.
- Use `watch` when a child update should call
  `parent.onDependencyNotify(child)` and then notify the parent.
- Every parent object generation lazily owns one stable dependency binding. It
  supplies a private child identity, keeps resolved children alive for at least
  the parent's lifetime, and mirrors current root owners in real time.
- Ownership is source-aware. Direct and multiple parent paths sharing one
  visible binding id are released independently.
- Synchronous propagation is transaction-based; each binding updates at most
  once even in a diamond graph.

## Lifecycle controls and safety

- Routine cleanup is binding-driven; do not call lifecycle hooks directly.
- `recycle(vm)` is a destructive global escape hatch. It removes every owner
  path and force-disposes the managed object, including `aliveForever`.
- There is no in-place replacement capability. Use a new explicit key for an
  independent instance. If global replacement is intentional, call `recycle`
  and let getter-based `watch(spec)` / `read(spec)` create a new handle and
  dependency tree on the next access; do not migrate old relationships.
- Always re-resolve through a property after `recycle`; a stored reference
  points to the disposed generation.
- Recursive construction and runtime ownership cycles throw `ViewModelError`.
  A failed build rolls back children created by its dependency scope.

Lifecycle hooks are `onCreate`, `onBind`, `onUnbind`, and `onDispose`. Register
owned resources with `addDispose` and let the framework invoke cleanup.

## State and observation

- Choose `ViewModel` for commands/services or broad change events.
- Choose `StateViewModel<State>` for immutable state and state diffs; neither is
  universally preferred.
- `setState` is the only operation that emits a state diff. A plain
  `notifyListeners()` only reaches broad ViewModel listeners.
- Full-state equality is constructor `equals` → global
  `ViewModel.config.equals` → reference identity.
- `listenStateSelect` compares selected values with Kotlin equality (`!=`).
  Unlike current Flutter `view_model`, it has no local selector-`equals`
  argument.
- For selector-level observation, use a read-style resolution and let the
  selector/listener own updates instead of also adding a broad `watch`.

## Main-thread and host rules

- Construct and use ViewModels on the main thread.
- Call `setState`, `notifyListeners`, `watch`, `read`, `recycle`,
  and `dispose` on the main thread.
- Use `viewModelScope` for asynchronous work and return to
  `Dispatchers.Main.immediate` before mutating state.
- Use `rememberRetainedViewModelBinding()` for ordinary Compose screens hosted
  by Activity/Fragment. Use `rememberViewModelBinding()` only for intentionally
  short-lived local composition scope.
- `View.viewModelBinding` ends at detach; use a tree/owner binding when state
  must survive View recreation.

## Pitfalls to catch

1. Recommending a keyed `aliveForever` singleton for every service.
2. Using AndroidX `ViewModel` as the business base.
3. Caching a resolved ViewModel in `by lazy` or another long-lived field.
4. Assuming `read` is non-binding; it still owns the instance.
5. Using cached lookup as a replacement for a stable spec.
6. Resolving any unkeyed `aliveForever` ViewModel, at root or nested scope.
7. Registering `listen` inside a resolver property.
8. Pairing selector observation with a broad `watch` subscription.
9. Creating specs inside Composables or render methods.
10. Calling public APIs from a background thread.
11. Forgetting to close a plain-class binding scope.
12. Using a detach-scoped View binding for retained screen state.

## Tests and mocks

- Tests must run in one JVM fork and in runner order because registry, config,
  lifecycle, reset, and spec-proxy state are process-global. Do not enable
  parallel forks, test sharding, or concurrent runners.
- `android-view-model/build.gradle.kts` must keep
  `maxParallelForks = 1`; downstream CI must preserve this invariant.
- Put constructor calls inside `viewModelSpec` builders. Resolve managed
  instances through a test binding instead of constructing them directly.
- Do not retain ViewModels in test fields; use a getter backed by the test
  binding.
- Dispose every test binding and reset global state between cases.
- Use `setProxy` / `clearProxy` in `try/finally` for mocks.

```kotlin
private lateinit var binding: ViewModelBinding
private val feature: FeatureViewModel
    get() = binding.read(featureSpec)

@Before
fun setUp() {
    InstanceManager.debugReset()
    ViewModel.debugReset()
    binding = ViewModelBinding()
}

@After
fun tearDown() {
    binding.dispose()
    InstanceManager.debugReset()
    ViewModel.debugReset()
}
```

## Platform differences from Flutter

- Android host retention uses AndroidX `ViewModelStoreOwner`; business
  ViewModels remain framework-owned.
- There is no `ObservableValue`, Flutter DevTools extension, `@GenSpec`
  generator, scoped `overrideWith/runWithOverride`, route pause provider, or
  ticker pause provider in this port.
- Android exposes `viewModelScope` and strict main-thread assertions.
- Selector equality currently uses Kotlin equality directly rather than
  Flutter's optional local/global selector comparator chain.

## Verification and dependency guidance

Run library verification serially:

```bash
./gradlew :android-view-model:testDebugUnitTest \
  :android-view-model:assembleDebug \
  :android-view-model:lintDebug \
  --no-parallel --max-workers=1
```

For Gradle dependencies, use `api` only when a dependency type appears in
public signatures and `implementation` for internal implementation details.
Android modules use `org.jetbrains.kotlin.android`, not `kotlin("jvm")`.
