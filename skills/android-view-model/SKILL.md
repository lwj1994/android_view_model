---
name: android-view-model
description: Triggered when using AndroidViewModel in Kotlin Android projects. Covers melu.viewmodel ViewModel / StateViewModel, ViewModelSpec declarations, ViewModelBinding, Compose bindings, Activity / Fragment / View / plain class integration, VM-to-VM DI, main-thread constraints, lifecycle disposal, and tests. Use when code imports melu.viewmodel or asks about this Android counterpart to apple_view_model.
---

# AndroidViewModel Skill

AndroidViewModel is a ViewModel registry and DI layer for Android, modeled after `apple_view_model`.

It intentionally does **not** implement `ObserverValue` / `ObservableValue`.

## Trigger Conditions

Activate when:

- Code imports `melu.viewmodel.*`
- Code uses `ViewModel`, `StateViewModel`, `ViewModelSpec`, `viewModelSpec`, `ViewModelBinding`, `watchViewModel`, or `viewModelBinding`
- The user asks about AndroidViewModel, an Android counterpart to AppleViewModel, shared ViewModel services, key/tag scoped DI, lifecycle disposal, or Compose/Fragment/Activity/View integration
- The user reports lifecycle, sharing, main-thread, or Gradle setup issues involving this framework

## Core Model

Use this mental model:

- `ViewModel`: business/service base class with `listen`, `notifyListeners`, `update`, `addDispose`, `viewModelScope`, and lifecycle hooks.
- `StateViewModel<State>`: immutable state base class with `state`, `previousState`, `setState`, `listenState`, and `listenStateSelect`.
- `ViewModelSpec<VM>`: factory declaration. A non-null `key` shares the instance across bindings.
- `ViewModelBinding`: scope/container held by Activity, Fragment, Compose, View, or a plain class.

Business `melu.viewmodel.ViewModel` does **not** extend AndroidX `ViewModel`. AndroidX `ViewModel` is only used internally to retain `ViewModelBinding` for `ViewModelStoreOwner` hosts.

All public ViewModel APIs are main-thread only. Prefer APIs annotated with `@MainThread`, and do not mutate state from background threads.

## Choosing a Base Class

| Need | Recommend | Notes |
| --- | --- | --- |
| Service, repository, cache, controller | `ViewModel` | Use `update {}` only when listeners need notification. |
| Immutable UI/business state | `StateViewModel<State>` | Prefer for Compose and screen-level state. |
| Cross-module singleton service | `ViewModel` + keyed spec | Use `aliveForever = true` when it should outlive bindings. |

Example:

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
```

## Declaring Specs

Prefer module-level specs.

```kotlin
val counterSpec = viewModelSpec(key = "counter") {
    CounterViewModel()
}

val authSpec = viewModelSpec(
    key = "auth",
    aliveForever = true,
) {
    AuthViewModel()
}
```

Use arg specs when constructor arguments determine sharing:

```kotlin
val userSpec = viewModelSpecWithArg<UserViewModel, String>(
    builder = { userId -> UserViewModel(userId) },
    key = { userId -> "user-$userId" },
)

val user = binding.watch(userSpec("42"))
```

## Choosing a Binding

| Context | Recommend | Lifecycle |
| --- | --- | --- |
| Compose retained by Activity/Fragment owner | `rememberRetainedViewModelBinding()` | Cleared with current `ViewModelStoreOwner`. |
| Compose local composition only | `rememberViewModelBinding()` | Disposed when composition leaves. |
| Compose read with rebuild | `watchViewModel(spec)` | Subscribes to VM changes. |
| Compose read without rebuild | `readViewModel(spec)` | Binds but does not subscribe. |
| Activity | `viewModelBinding.watch(spec)` | Cleared with Activity `ViewModelStore`. |
| Fragment instance | `viewModelBinding.watch(spec)` | Cleared with Fragment `ViewModelStore`. |
| Fragment view lifecycle | `viewLifecycleViewModelBinding.watch(spec)` | Disposed when Fragment view is destroyed. |
| Fragment shared with Activity | `activityViewModelBinding.read(spec)` | Uses Activity binding. |
| Custom View | `viewModelBinding.watch(spec)` | Disposed on detach from window. |
| View tree owner | `viewTreeViewModelBinding.watch(spec)` | Reuses nearest `ViewModelStoreOwner`. |
| Plain class | `ViewModelBindingScope()` | Caller must `close()` / `dispose()`. |

## watch vs read

- `watch(spec)` = create if needed + bind + subscribe to `notifyListeners`
- `read(spec)` = create if needed + bind, no subscription
- `watchCached<T>(key/tag)` = lookup existing + bind + subscribe, throws on miss
- `readCached<T>(key/tag)` = lookup existing + bind, throws on miss
- `maybe*Cached` variants return `null` on miss

Use `read` for service dependencies unless the parent host must refresh when the dependency changes.

## Compose Pattern

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

Use `rememberRetainedViewModelBinding()` in screens hosted by Activity/Fragment. Use `rememberViewModelBinding()` only for short-lived local scopes.

## Activity / Fragment / View Patterns

```kotlin
class MainActivity : FragmentActivity() {
    private val counter by lazy { viewModelBinding.watch(counterSpec) }
}
```

```kotlin
class CounterFragment : Fragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val counter = viewLifecycleViewModelBinding.watch(counterSpec)
    }
}
```

```kotlin
class CounterPanelView(context: Context) : LinearLayout(context) {
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val counter = viewModelBinding.watch(counterSpec)
    }
}
```

## Plain Class Pattern

```kotlin
class CounterController : AutoCloseable {
    private val scope = ViewModelBindingScope()
    private val counter = scope.viewModelBinding.read(counterSpec)

    fun increment() {
        counter.increment()
    }

    override fun close() {
        scope.close()
    }
}
```

## VM-to-VM DI

Inside a ViewModel, use `viewModelBinding` to resolve dependencies created by the same binding.

```kotlin
class OrderViewModel : ViewModel() {
    val auth: AuthViewModel = viewModelBinding.read(authSpec)
}
```

Prefer `read` for dependencies. Use `watch` only if this ViewModel should notify when the dependency changes through binding updates.

## Main-Thread Rules

Check for these when debugging:

- Construct and use ViewModels on the main thread.
- Call `setState`, `notifyListeners`, `watch`, `read`, and `dispose` on the main thread.
- Use `viewModelScope` for async work, then switch back to `Dispatchers.Main.immediate` before state mutation.
- Do not bypass the framework by calling lifecycle hooks directly in app code.

## Pitfalls to Catch

1. **Using AndroidX ViewModel as the business base**: use `melu.viewmodel.ViewModel` or `StateViewModel` instead.
2. **Missing key on shared services**: unkeyed specs create a new instance per `watch/read` call.
3. **Creating specs inside Composables or render methods**: prefer module-level specs so sharing and test proxies remain stable.
4. **Forgetting to close plain-class scopes**: `ViewModelBindingScope` must be closed by the owner.
5. **Using View binding for retained screen state**: `View.viewModelBinding` is disposed on detach; use `viewTreeViewModelBinding` or owner binding when retention is required.
6. **Mutating state in place**: `StateViewModel` expects a full new immutable state value via `setState`.
7. **Calling from background threads**: APIs are main-thread only and runtime assertions can fail fast.
8. **Gradle plugin leakage**: Android modules should use `org.jetbrains.kotlin.android`, not `kotlin("jvm")`.

## Tests

Standard test reset:

```kotlin
@Before
fun setUp() {
    InstanceManager.debugReset()
    ViewModel.debugReset()
}

@After
fun tearDown() {
    InstanceManager.debugReset()
    ViewModel.debugReset()
}
```

Spec proxy pattern:

```kotlin
spec.setProxy(viewModelSpec(key = "auth") { MockAuthViewModel() })
try {
    // test code
} finally {
    spec.clearProxy()
}
```

Run verification:

```bash
./gradlew :android-view-model:testDebugUnitTest :example:assembleDebug
```

## Dependency Guidance

For the library module:

- Use `api` only when a dependency type appears in public API signatures.
- Use `implementation` for internal implementation dependencies.
- Keep AndroidX `ViewModel` usage in host-retention code, not in business ViewModel inheritance.

