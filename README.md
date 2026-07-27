# AndroidViewModel

> Changelog: [CHANGELOG](./CHANGELOG.md) · Releases: [GitHub Releases](https://github.com/lwj1994/android_view_model/releases)

AndroidViewModel is a ViewModel registry, module-composition, and DI layer.

It keeps the core service model independent from any single Android host:

- `ViewModel` is the business base class.
- `StateViewModel<State>` manages immutable state and emits state/general listeners.
- `ViewModelSpec` declares how to build a ViewModel and whether it is shared by `key`.
- `ViewModelBinding` is the scoped container used by Activity, Fragment, Compose, View, or plain classes.

Every functional unit can be a ViewModel: UI state, repositories, services, coordinators, or domain capabilities. Each managed parent object generation owns a stable dependency binding. Child modules are created only when a resolver property is accessed, remain alive for at least the parent's lifetime, and are released automatically.

Instance identity is the resolved ViewModel type plus its effective key. An unkeyed spec uses a private key owned by the current binding, so repeated resolution of the same type reuses one instance inside that binding while different bindings remain isolated. Use explicit keys for cross-binding sharing or multiple instances of the same type in one binding.

## Core resolution rules

> [!IMPORTANT]
> The default path is always **stable spec → `watch(spec)` / `read(spec)`**.
> A spec may contain a key or tag and should still be passed through these APIs;
> knowing cache identity is not a reason to bypass the spec.

- Keep specs stable and module-level. Use `watch(spec)` or `read(spec)` as the primary entry points in Compose, host classes, tests, and ViewModel-to-ViewModel dependencies.
- `watch` and `read` both create or reuse an instance, establish lifecycle ownership, and observe handle disposal, including force-recycle. Only `watch` listens to the ViewModel's own `notifyListeners()`.
- Prefer binding-managed modules over global singletons. A normal feature, service, repository, or coordinator should use an unkeyed spec with `aliveForever = false`.
- Cached APIs are advanced lookup-only escape hatches. They cannot create a missing instance and should not replace spec-based dependency resolution.
- Resolve ViewModels through resolver properties instead of `by lazy` or stored references so explicit recycle and asynchronous lifecycle changes can return the current generation.

## Quick Start

Add JitPack to your root `settings.gradle.kts`.

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.lwj1994")
            }
        }
    }
}
```

Add the dependency in your app or library module.

```kotlin
dependencies {
    implementation("com.github.lwj1994:android_view_model:v0.3.0")
}
```

Create a ViewModel and a spec.

```kotlin
import milu.viewmodel.StateViewModel
import milu.viewmodel.viewModelSpec

data class CounterState(val count: Int = 0)

class CounterViewModel : StateViewModel<CounterState>(
    initialState = CounterState(),
    equals = { a, b -> a == b },
) {
    fun increment() {
        setState(state.copy(count = state.count + 1))
    }
}

val counterSpec = viewModelSpec {
    CounterViewModel()
}
```

`key`, `tag`, and `aliveForever` have separate jobs:

- `key` participates in identity. Use it for intentional cross-binding sharing or multiple same-type instances in one binding.
- `tag` is only a grouping/lookup label.
- `aliveForever` skips automatic disposal when all ownership paths leave; explicit `recycle` and `InstanceManager.debugReset()` still force disposal.
- Every `aliveForever` spec must have an explicit key, whether resolved by a root binding or another ViewModel. A missing or computed-null key throws `ViewModelError` before the builder runs, and the Store enforces the same invariant for internal factories.

Bind it to the host you are using.

```kotlin
// Compose
ViewModelBindingProvider(binding = rememberRetainedViewModelBinding()) {
    val counter = watchViewModel(counterSpec)
}

// Activity
val counter = viewModelBinding.watch(counterSpec)

// Fragment view lifecycle
val counter = viewLifecycleViewModelBinding.watch(counterSpec)

// Plain class
val scope = ViewModelBindingScope()
val counter = scope.viewModelBinding.read(counterSpec)
```

## Why not extend AndroidX ViewModel?

The business `milu.viewmodel.ViewModel` intentionally does not extend AndroidX `ViewModel`.

AndroidX `ViewModel` is scoped to one `ViewModelStoreOwner`. This library needs a different lifecycle model: a keyed instance may be shared across multiple Activities, Fragments, Views, Compose scopes, and plain classes, and is disposed when the last `ViewModelBinding` releases its reference.

AndroidX is still used at the host layer. `ViewModelStoreOwner.viewModelBinding` stores an internal AndroidX ViewModel whose only job is to retain and clear the `ViewModelBinding`.

## Use From Git Source

JitPack is the recommended integration path. If you want Gradle to clone and build the GitHub source directly, use Gradle source dependencies instead.

Gradle will clone the GitHub repository, check out the requested branch or tag, and build `:android-view-model` locally.

In your app's `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

sourceControl {
    gitRepository(uri("https://github.com/lwj1994/android_view_model.git")) {
        producesModule("android_view_model:android-view-model")
    }
}
```

In your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("android_view_model:android-view-model") {
        version {
            branch = "main"
        }
    }
}
```

For a stable dependency, prefer a Git tag once one exists:

```kotlin
dependencies {
    implementation("android_view_model:android-view-model:v0.3.0")
}
```

When using Gradle source dependencies for Android builds, set `ANDROID_HOME` or `ANDROID_SDK_ROOT`. A root `local.properties` file is not visible to the Git checkout that Gradle builds as the dependency.


This avoids Maven for this library itself. `google()` and `mavenCentral()` are still required for Android Gradle Plugin, Kotlin, AndroidX, and Compose dependencies.

## Basic Usage

```kotlin
data class CounterState(val count: Int = 0)

class CounterViewModel : StateViewModel<CounterState>(
    initialState = CounterState(),
    equals = { a, b -> a == b },
) {
    fun increment() {
        setState(state.copy(count = state.count + 1))
    }
}

val counterSpec = viewModelSpec {
    CounterViewModel()
}
```

### Compose

```kotlin
@Composable
fun CounterScreen() {
    ViewModelBindingProvider(binding = rememberRetainedViewModelBinding()) {
        val counter = watchViewModel(counterSpec)
        Button(onClick = counter::increment) {
            Text("${counter.state.count}")
        }
    }
}
```

### Activity / Fragment

```kotlin
class MainActivity : FragmentActivity() {
    private val counter: CounterViewModel
        get() = viewModelBinding.watch(counterSpec)
}

class CounterFragment : Fragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val counter = viewLifecycleViewModelBinding.watch(counterSpec)
    }
}
```

### View

```kotlin
class CounterPanelView(context: Context) : LinearLayout(context) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val counter = viewModelBinding.watch(counterSpec)
    }
}
```

### Plain Class

```kotlin
class CounterController : AutoCloseable {
    private val scope = ViewModelBindingScope()
    private val counter: CounterViewModel
        get() = scope.viewModelBinding.read(counterSpec)

    fun increment() = counter.increment()

    override fun close() {
        scope.close()
    }
}
```

## Binding access APIs

### Primary: spec-based resolution (recommended)

Normal application code should keep a stable spec and use one of these APIs:

| API | Creates if absent? | Establishes ownership? | VM `notifyListeners()` | Handle disposal |
|---|---:|---:|---:|---:|
| `watch(spec)` | Yes | Yes | Yes | Yes |
| `read(spec)` | Yes | Yes | No | Yes |

Choose `watch` when ViewModel notifications should update the owner. Choose
`read` for lifecycle-bound access without subscribing to those notifications.

### Advanced: cached lookup

> [!CAUTION]
> Do not use cached lookup as a substitute for spec-based dependency
> resolution. It reaches into an instance that another path must already have
> created, couples the caller to cache identity, creation order, and another
> owner's lifecycle, and cannot create a missing dependency. Use it only for an
> intentional cross-owner query of an existing cache entry.

| API | Creates if absent? | Establishes ownership? | VM `notifyListeners()` | Handle disposal |
|---|---:|---:|---:|---:|
| `watchCached<T>(key/tag)` | No | Yes | Yes | Yes |
| `readCached<T>(key/tag)` | No | Yes | No | Yes |
| `maybeWatchCached<T>` | No; returns `null` | Yes on hit | Yes | Yes |
| `maybeReadCached<T>` | No; returns `null` | Yes on hit | No | Yes |
| `watchCachesByTag<T>` | No; returns all hits | Yes | Yes | Yes |
| `readCachesByTag<T>` | No; returns all hits | Yes | No | Yes |

Single-result non-`maybe` lookups throw on a miss, and tag lookup can be
ambiguous when several instances share a tag. If the caller has a spec—even a
keyed or tagged spec—use `watch(spec)` / `read(spec)` instead.

`listen`, `listenState`, and `listenStateSelect` resolve through `read` and are automatically removed when the target handle or binding disposes. They are not migrated to another object. Do not put a `listen` call in a repeatedly evaluated resolver property.

## ViewModel-to-ViewModel dependencies

Expose nested ViewModels through resolver properties. Do not retain a child in a stored property or ad-hoc cache: explicit `recycle` or an asynchronous lifecycle race must allow the next access to resolve the current generation.

```kotlin
val sessionSpec = viewModelSpec { SessionViewModel() }
val cartSpec = viewModelSpec { CartViewModel() }

class CheckoutViewModel : ViewModel() {
    val session: SessionViewModel
        get() = viewModelBinding.read(sessionSpec)

    val cart: CartViewModel
        get() = viewModelBinding.watch(cartSpec)
}
```

Use `read` when the parent only calls the child. Use `watch` when child notifications should call `parent.onDependencyNotify(child)` and then notify the parent. Synchronous propagation is transaction-based, so diamond dependency graphs update each binding at most once.

A keyed parent can be shared by several root bindings. Roots joining or leaving are mirrored to already-resolved children without changing an unkeyed child's identity. Ownership paths are source-aware: one root may own a keyed child directly and through several parents, and releasing one path does not remove the others. Every `aliveForever` spec must use an explicit key at both root and nested resolution sites.

Getter declarations create nothing by themselves. After a child is resolved, the parent generation owns a `parent → child` lifecycle edge. The child may outlive its parent if another direct or parent path still owns it, but it cannot be disposed while that parent generation still owns it.

## Lifecycle controls

- `recycle(vm)` is a destructive global escape hatch. It removes every owner and disposes the shared object, including `aliveForever` instances.

There is no in-place instance replacement API. To obtain an independent instance, use a new explicit key. If replacing the shared cached generation globally is intentional, call `recycle(vm)` and let resolver properties call `watch(spec)` / `read(spec)` again. The cache miss creates a new handle and dependency tree; owner paths, watch/listen subscriptions, and dependency edges are not migrated from the disposed object.

After `recycle`, access ViewModels through resolver properties; a stored reference keeps pointing at the disposed object.

Construction and dependency graphs are checked. Recursive construction and runtime ownership cycles throw `ViewModelError`; a failed build rolls back children created by that dependency scope.

## State and fine-grained observation

- `setState` is the only operation that emits a state diff; `notifyListeners()` only reaches broad ViewModel listeners.
- Full-state equality is constructor `equals` → `ViewModel.config.equals` → reference identity.
- `listenStateSelect` compares selected values with Kotlin equality (`!=`). The current Android API does not expose Flutter's local selector-`equals` argument.
- For selector-level UI observation, obtain the ViewModel with a read-style API and let the selector own updates; do not add a broad `watch` subscription to the same instance.

## Threading

The public ViewModel API is main-thread only. Core public classes/functions are annotated with `@MainThread`, and runtime assertions catch accidental calls from background threads.

Use `viewModelScope` for async work and hop back to the main thread before mutating state.

## Testing

- Tests must run in one JVM fork and in runner order. Do not enable Gradle
  parallel test forks, test sharding, or concurrent test runners: registry,
  configuration, lifecycle, reset, and spec-proxy state are process-global.
- The library Gradle module enforces `maxParallelForks = 1`; keep this invariant
  in downstream CI and do not add `--parallel` to the verification command.
- Put constructor calls inside `viewModelSpec` builders and resolve managed instances through a test binding; do not instantiate a ViewModel directly in a test body or `setUp`.
- Do not retain ViewModels in test fields. Use a getter backed by the test binding when a shared fixture is needed.
- Dispose every binding, and reset `InstanceManager` / `ViewModel` between isolated tests.
- Use `setProxy` / `clearProxy` in `try/finally` for mocks.

```kotlin
private lateinit var binding: ViewModelBinding
private val counter: CounterViewModel
    get() = binding.read(counterSpec)

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

## Example

The `example` module demonstrates all supported host styles:

- Compose with `rememberRetainedViewModelBinding`
- Activity with `viewModelBinding`
- Fragment with `viewLifecycleViewModelBinding` and `activityViewModelBinding`
- Custom View with `viewModelBinding`
- Plain class with `ViewModelBindingScope`

Build it with:

```bash
./gradlew :example:assembleDebug
```

Run tests with:

```bash
./gradlew :android-view-model:testDebugUnitTest --no-parallel --max-workers=1
```
