---
name: android-view-model
description: Use AndroidViewModel in Kotlin Android projects for state management, functional-module composition, dependency injection, automatic lifecycle, Compose/Activity/Fragment/View bindings, ViewModel-to-ViewModel dependencies, process-local sharing, Android cross-process state synchronization, threading, and tests.
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
- Cross-process example:
  `example/src/main/kotlin/milu/viewmodel/example/ProcessCounterExample.kt`
- Conceptual upstream: Flutter `view_model` README and skill

If this skill conflicts with the repository README or tests, follow the current
repository and update the skill.

## Trigger conditions

Use this skill when:

- Code imports `milu.viewmodel.*` or uses `ViewModel`, `StateViewModel`,
  `ViewModelSpec`, `ViewModelBinding`, `watchViewModel`, or `readViewModel`.
- The task concerns state, DI, module composition, lifecycle, sharing,
  Android process boundaries, main-thread behavior,
  Compose/Activity/Fragment/View integration, or tests.

## Resolution decision order (must follow)

1. Keep a stable, module-level spec. Do this for Compose, Android hosts, plain
   bindings, tests, and ViewModel-to-ViewModel dependencies.
2. Declare `val vm by watchViewModel(spec)` when ViewModel notifications should
   update the owner, or `val vm by readViewModel(spec)` without broad observation.
   Outside Compose, use a fixed `binding.watchViewModel/readViewModel(spec)`
   receiver or a deferred binding lambda as described below.
   Both delegates create/reuse, bind, and observe handle disposal, including
   force-recycle. Do not require handwritten getters in business code.
3. Use a cached API only when the task explicitly requires an advanced
   cross-owner query of an instance already created elsewhere. Cached APIs
   cannot create a missing dependency and must not be suggested as normal DI.

A key or tag on a spec does not change this order. Pass the keyed/tagged spec to
`watchViewModel` or `readViewModel`; knowing cache identity is not a reason to bypass the spec.

## Resolved-instance ownership boundary (must follow)

- Treat the stable spec as the shareable declaration and a resolved ViewModel
  as local to the binding graph, owner path, and generation that resolved it.
- Never pass a resolved `ViewModel` or `StateViewModel` instance across
  components, layers, hosts, bindings, or owner boundaries, and flag this
  pattern during review. Passing an instance does not register ownership for
  the receiver; it can leave the receiver holding a disposed generation after
  recycle, let the receiver outlive the owning binding, or retain the instance
  beyond its intended lifecycle.
- Pass the stable spec and resolve it at each consuming owner. Use `by` delegates
  for ViewModel-to-ViewModel dependencies. Across UI boundaries,
  pass immutable render values and event callbacks.
- An explicit key can make independent consumers resolve the same managed
  instance. It does not make transporting the resolved instance safe.

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
  Explicit `recycle` and the complete `ViewModel.reset()` still dispose it.
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

### Local scope: sharing one instance across pages

A common case is for screen A to display data and screen B to edit it. Screen A
must see B's changes when B closes; if both destinations are active, A should
react to changes immediately. Prefer the same spec with an explicit key and
default auto-disposal. Do not set `aliveForever = true` merely to share across
screens:

```kotlin
class DraftViewModel(
    val documentId: String,
) : ViewModel() {
    var title: String = ""
        private set

    fun updateTitle(value: String) = update {
        title = value
    }
}

val draftViewModelSpec = viewModelSpecWithArg<DraftViewModel, String>(
    builder = ::DraftViewModel,
    key = { documentId -> "draft:$documentId" },
)

@Composable
fun PageA(documentId: String) {
    ViewModelBindingProvider(binding = rememberScreenViewModelBinding()) {
        val draft by watchViewModel(draftViewModelSpec(documentId))
        Text(draft.title)
    }
}

@Composable
fun PageB(documentId: String) {
    ViewModelBindingProvider(binding = rememberScreenViewModelBinding()) {
        val draft by watchViewModel(draftViewModelSpec(documentId))
        TextField(
            value = draft.title,
            onValueChange = { draft.updateTitle(it) },
        )
    }
}
```

- A and B resolve the same instance because they use the same resolved
  ViewModel type and key. There is no need to use a cached API to retrieve an
  instance created by the other screen.
- Both delegates bind the instance to the current screen. Use
  `by watchViewModel(spec)` for ViewModel notifications and
  `by readViewModel(spec)` when the screen only invokes methods.
- While A and B both exist, each retained destination binding owns the instance.
  Removing B releases only B's ownership, so A keeps the instance alive. When A
  is also removed, the final ownership path leaves and the instance is
  automatically disposed.
- A `key` defines shared identity; it does not retain the instance forever. If
  multiple edit flows can coexist, include a document or session ID in the key
  so unrelated flows do not share state.
- The resulting lifetime is the union of all participating screen scopes. This
  is usually more appropriate than `aliveForever = true`. Use `aliveForever`
  with an explicit key only when the instance must survive with zero bindings.
- In Fragment navigation, use each destination Fragment's `viewModelBinding`
  for the same behavior. Use `viewLifecycleViewModelBinding` only when sharing
  should end as soon as that Fragment's view is destroyed; use
  `activityViewModelBinding` only when Activity-wide ownership is intentional.

## Android process boundary: share state, never an instance

Explicit keys share an instance only inside one Android process. Every process
has its own ART heap, static registry, bindings, lifecycle, coroutine scopes,
and ViewModel instances. Never claim that the same key creates one object
across processes.

Use `ProcessStateStore<State>` only when an app explicitly runs components in
different processes and needs to synchronize `StateViewModel` snapshots:

- Prefer `ParcelableProcessStateStore<State : Parcelable>` for Android Binder
  or `Bundle` transport. State classes normally implement `Parcelable` through
  `@Parcelize`.
- The Store implementation supplies the actual IPC mechanism, such as a
  non-exported `ContentProvider` or Binder Service. The interface itself is not
  IPC.
- `observe()` must register for changes before reading its initial snapshot,
  emit the current record when present, and then emit every accepted update.
  `StateViewModel` intentionally consumes this continuous stream rather than a
  separate `read()` followed by `observe()`.
- `ProcessStateRecord.version` and `sourceId` provide deterministic conflict
  ordering and prevent remote updates from echoing back as local writes. The
  IPC authority must reject older records and notify clients even when a
  concurrent candidate loses, so that loser observes the winner.
- This synchronizes state, not ViewModel identity, lifecycle, commands,
  listeners, or coroutine jobs. Use Binder/AIDL command APIs separately when
  remote method invocation is the actual requirement.
- Parcelable is a transient IPC format. If the state must survive termination
  of the authority process, add a durable, explicitly versioned persistence
  format behind the Store.
- Do not introduce cross-process synchronization for ordinary navigation or
  sibling pages. Use the same keyed spec and binding-scoped ownership described
  above.

The runnable example uses three real processes: `MainActivity` in the default
process, `RemoteProcessActivity` in `:remote`, and a non-exported
`ProcessCounterStateProvider` in `:state_store`. Its in-memory Provider is an IPC
demonstration, not process-death persistence.

## Property delegates: the business-code entry point

- In Compose, use `val vm by watchViewModel(spec)` or `readViewModel(spec)`;
  these functions return delegates.
- For an existing fixed binding, prefer `val vm by binding.readViewModel(spec)`
  or `binding.watchViewModel(spec)`. The receiver is captured at declaration;
  reassigning the binding variable does not retarget the delegate.
- For deferred or changing bindings, use
  `val vm by readViewModel(spec) { viewModelBinding }` or its watch counterpart.
  Keep this form for early Activity/Fragment properties, Fragment view lifecycle,
  reattached Views, test fields initialized before setup, and lazy nested
  dependency bindings.
- Both forms resolve the VM on every access without caching an instance. With
  the same binding and spec they are equivalent; fixed delegates reject access
  after their captured binding is disposed, while a lambda can look up a new one.
  Ownership starts on first access. Compose additionally resolves
  and subscribes during composition; callbacks after recycle can resolve the
  current generation without waiting for recomposition.
- Do not use `by lazy`, `remember { vm }`, stored VM references, or cross-owner
  delegate passing.
- Use `{ vm.action() }` callbacks, not `vm::action`, which captures an old instance.
- The stable spec is determined when the delegate is declared; old callbacks
  must stay within the original owner's lifetime.
- `watchViewModelState/selectViewModelState` return render values. Binding
  `watch/read` implements resolution; cached APIs remain advanced queries.

In Compose, keep using the top-level composable overload, even with a fixed local
binding: `val vm by readViewModel(spec, binding = binding)` or its watch version.
Receiver extensions do not establish Compose recomposition subscriptions and
must not replace these calls for UI access. Avoid implicit-receiver forms such
as `with(binding) { readViewModel(spec) }` in Compose, which can select the ordinary
extension instead of the composable function.

Internally, the delegate's `getValue()` resolves on each access. Business code
does not need handwritten getters.

## Choosing a binding

| Context | Recommended API | Lifecycle |
| --- | --- | --- |
| Compose current-owner screen scope | `rememberScreenViewModelBinding()` | Cleared with current `ViewModelStoreOwner`. |
| Compose local composition | `rememberViewModelBinding()` | Disposed when composition leaves. |
| Compose broad rebuild | `by watchViewModel(spec)` | Subscribes to VM notifications. |
| Compose access without broad rebuild | `by readViewModel(spec)` | Bound, no VM-wide subscription. |
| Compose selected state | `selectViewModelState(spec, selector, equals?)` | Read-style ownership; recomposes only for selected changes. |
| Activity / Fragment instance | `by watchViewModel(spec) { viewModelBinding }` | Cleared with host `ViewModelStore`. |
| Fragment view lifecycle | `by watchViewModel(spec) { viewLifecycleViewModelBinding }` | Disposed with the Fragment view. |
| Activity-shared Fragment access | `by watchViewModel(spec) { activityViewModelBinding }` | Uses Activity ownership. |
| Custom View local scope | `by watchViewModel(spec) { viewModelBinding }` | Disposed on detach. |
| View tree owner | `by watchViewModel(spec) { viewTreeViewModelBinding }` | Reuses nearest owner binding. |
| Plain class / tests, existing fixed binding | `by binding.readViewModel(spec)` or watch counterpart | Caller must close/dispose. |

Declare host ViewModels with `by` delegates:

```kotlin
class MainActivity : FragmentActivity() {
    private val orders: OrdersViewModel by watchViewModel(ordersSpec) { viewModelBinding }
}
```

## Underlying binding resolution semantics

Business code uses the `by` delegates above. This table describes the resolution
mechanism used inside those delegates.

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

`maybeWatchCached` and `maybeReadCached` swallow only `ViewModelError` misses.
They must not hide unrelated exceptions from key/tag code or the runtime.

`listen`, `listenState`, and `listenStateSelect` are binding-owned side effects.
They resolve through `read` and are removed when the target handle or binding is
disposed. They are never migrated to another object. Never place a `listen` call
inside a delegate's binding lambda; register listeners once during initialization.

## Response pattern for implementation requests

- Default every normal resolution example to a stable spec plus
  `by watchViewModel(spec)` or `by readViewModel(spec)`, with a fixed receiver or deferred binding lambda
  outside Compose.
- Preserve spec-based resolution in refactors and migrations. Never introduce a
  cached API merely because a key or tag is available.
- For temporary sharing across screens or independent bindings, let every
  participant use a `by` delegate with the same keyed spec. Their bindings
  collectively define the local lifetime, and the instance auto-disposes after
  the final participant unbinds.
- Distinguish process-local instance sharing from Android cross-process state
  synchronization. Recommend `ProcessStateStore` only for an explicit
  multi-process requirement, and state that it never shares the instance.
- Show cached lookup only when the user explicitly needs an already-created
  cross-owner cache entry, and state that absence, creation order, tag
  multiplicity, and the other owner's lifecycle are part of the contract.
- Default ordinary modules to an unkeyed spec with `aliveForever = false`; add a
  key or retention only when sharing or retention is intentional.
- Prefer keyed, binding-scoped sharing over `aliveForever` when an instance only
  needs to live while one or more participating screens are alive.

## ViewModel-to-ViewModel composition

Declare dependencies with `by watchViewModel/readViewModel` delegates. Do not retain a nested
ViewModel in `by lazy`, a stored property, or an ad-hoc cache.

```kotlin
val cartSpec = viewModelSpec { CartViewModel() }
val pricingSpec = viewModelSpec { PricingViewModel() }

class CheckoutViewModel : ViewModel() {
    val cart: CartViewModel by readViewModel(cartSpec) { viewModelBinding }

    val pricing: PricingViewModel by watchViewModel(pricingSpec) { viewModelBinding }
}
```

- A nested ViewModel delegate creates nothing until first accessed.
- Use `read` to call a child without bubbling its own notifications.
- Use `watch` to automatically forward child notifications through the parent,
  ultimately refreshing bindings that watch the parent. This remains distinct
  from `read`; removing a hook does not remove the watch subscription.
- There is no `onDependencyNotify` override hook. Business reactions use binding
  `listen`, `listenState`, or `listenStateSelect`, registered once during
  initialization, never in a delegate's binding lambda. Do not reintroduce a dependency
  update callback as a substitute for those explicit subscriptions.
- `listen*` uses read-style ownership and does not implicitly notify the parent.
  Call `setState`, `update`, or `notifyListeners` explicitly when the business
  reaction changes parent state that its consumers should observe.
- Both `read` and `watch` preserve ownership and handle-disposal observation;
  binding-owned `listen*` subscriptions end with their target handle or binding
  and are not migrated to a replacement generation after recycle.
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
  and access the delegated property again. The delegate creates a new handle
  and dependency tree automatically; do not migrate old relationships.
- Keep using the delegated property after `recycle`; a stored reference
  points to the disposed generation.
- Compose `watchViewModel` and `readViewModel` observe handle disposal and
  re-resolve their stable spec after recycle instead of retaining that disposed
  generation.
- `ViewModel.reset()` is the complete process-wide test reset: it force-disposes
  every cached generation before clearing configuration and lifecycle observers.
  The complete sequence is reentrancy-guarded so nested reset attempts from
  teardown cannot clear the active error/lifecycle pipeline early.
- Recursive construction and runtime ownership cycles throw `ViewModelError`.
  A failed build rolls back children created by its dependency scope.

Lifecycle hooks are `onCreate`, `onBind`, `onUnbind`, and `onDispose`. Register
owned resources with `addDispose` and let the framework invoke cleanup.

## State and observation

- Choose `ViewModel` for commands/services or broad change events.
- Choose `StateViewModel<State>` for immutable state and state diffs; neither is
  universally preferred.
- The resolved-instance ownership rule is strict at composable boundaries:
  never pass a `ViewModel` or `StateViewModel` instance as a child composable
  parameter. A composable boundary accepts immutable render values and event
  callbacks; alternatively, the consuming composable resolves the stable spec
  and observes it itself.
- `watchViewModel` invalidates only the composable scope that calls it. Under
  Compose strong skipping, passing the same ViewModel reference to a child does
  not transfer that observation. Pass immutable render values read in the
  watching scope, or observe the stable spec at the consuming scope.
- `setState` is the only operation that emits a state diff. A plain
  `notifyListeners()` only reaches broad ViewModel listeners.
- Full-state equality is constructor `equals` → global
  `ViewModel.config.equals` → reference identity.
- `listenStateSelect` and Compose `selectViewModelState` compare selected values
  with local `equals` → global `ViewModel.config.equals` → Kotlin `==`.
- A state transition freezes its previous/current pair before listener dispatch;
  a reentrant synchronous `setState` does not corrupt the pair delivered to
  later listeners.
- For selector-level observation, use a read-style resolution and let the
  selector/listener own updates instead of also adding a broad `watch`.

## Scoped spec overrides

Every zero- through four-argument spec supports `overrideWith` and
`runWithOverride` in addition to legacy `setProxy` / `clearProxy`.

- `overrideWith(fake)` returns an idempotent restore callback. It supports
  nesting and out-of-order restore; invoke it in `finally`.
- `runWithOverride(fake) { ... }` restores after success or failure, remains
  active across suspension points, and isolates overlapping coroutine scopes.
- An active proxy supplies the complete builder/key/tag/retention definition.
  Its nullable key/tag and `false` retention values never fall back to the base
  spec.

## Main-thread and host rules

- Construct and use ViewModels on the main thread.
- Call `setState`, `notifyListeners`, `watch`, `read`, `recycle`,
  and `dispose` on the main thread.
- Use `viewModelScope` for asynchronous work and return to
  `Dispatchers.Main.immediate` before mutating state.
- Keep `rememberViewModelBinding()` as the local default: one independent binding
  per call site, disposed when that call leaves the composition.
- Opt into `rememberScreenViewModelBinding()` for current-owner sharing and
  retention across configuration changes. In Navigation Compose, the owner is
  usually the destination entry; otherwise it can be an Activity/Fragment.
  Callers under the same owner share a binding, including unkeyed same-type VMs.
  Disposal follows ViewModelStore clearing, not composable exit or Lifecycle
  destruction alone. This does not restore instances after process death.
- The screen API reads `LocalViewModelStoreOwner`, not `LocalViewModelBinding`;
  without an owner it falls back to a local binding. `ViewModelBindingProvider()`
  and provider-free consumers remain local by default. Explicitly supply a screen
  binding to a provider to share it with descendants.
- `View.viewModelBinding` ends at detach; use a tree/owner binding when state
  must survive View recreation.

## Pitfalls to catch

1. Recommending a keyed `aliveForever` singleton for every service.
2. Using AndroidX `ViewModel` as the business base.
3. Caching a resolved ViewModel in `by lazy` or another long-lived field.
4. Assuming `read` is non-binding; it still owns the instance.
5. Using cached lookup as a replacement for a stable spec.
6. Resolving any unkeyed `aliveForever` ViewModel, at root or nested scope.
7. Registering `listen` inside a delegate's binding lambda.
8. Pairing selector observation with a broad `watch` subscription.
9. Creating specs inside Composables or render methods.
10. Calling public APIs from a background thread.
11. Forgetting to close a plain-class binding scope.
12. Using a detach-scoped View binding for retained screen state.
13. Claiming that an explicit key shares a ViewModel instance across Android
    processes.
14. Adding `ProcessStateStore` for ordinary page-to-page sharing that should use
    a keyed spec.
15. Passing a resolved ViewModel instance across an ownership boundary instead
    of passing its stable spec and resolving it at the consumer. For child
    composables this also loses the `watchViewModel` observation under strong
    skipping.
16. Overriding the removed dependency notification hook, or treating `watch` and
    `read` as interchangeable after its removal.

## Tests and mocks

- Tests must run in one JVM fork and in runner order because registry, config,
  lifecycle, reset, and spec-proxy state are process-global. Do not enable
  parallel forks, test sharding, or concurrent runners.
- `android-view-model/build.gradle.kts` must keep
  `maxParallelForks = 1`; downstream CI must preserve this invariant.
- Put constructor calls inside `viewModelSpec` builders. Resolve managed
  instances through a test binding instead of constructing them directly.
- Do not retain resolved ViewModels in test fields; use
  `by readViewModel(spec) { binding }` for shared fixtures.
- Dispose every test binding and call the complete `ViewModel.reset()` between cases.
- Prefer `runWithOverride` for coroutine mocks. Invoke an `overrideWith` restore
  callback in `finally`; legacy `setProxy` / `clearProxy` also requires
  `try/finally`.

```kotlin
private lateinit var binding: ViewModelBinding
private val feature: FeatureViewModel by readViewModel(featureSpec) { binding }

@Before
fun setUp() {
    ViewModel.reset()
    binding = ViewModelBinding()
}

@After
fun tearDown() {
    binding.dispose()
    ViewModel.reset()
}
```

## Platform differences from Flutter

- Android host retention uses AndroidX `ViewModelStoreOwner`; business
  ViewModels remain framework-owned.
- There is no `ObservableValue`, Flutter DevTools extension, `@GenSpec`
  generator, route pause provider, or ticker pause provider in this port.
- Android exposes `viewModelScope` and strict main-thread assertions.
- Android can synchronize Parcelable `StateViewModel` snapshots across
  processes through an app-provided `ProcessStateStore`; this is state
  transport, not shared object identity.
- Android coroutine context elements provide the async isolation that Flutter
  implements with Zones for `runWithOverride`.

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
