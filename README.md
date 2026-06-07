# AndroidViewModel

AndroidViewModel is a small ViewModel registry and DI layer.

It keeps the core service model independent from any single Android host:

- `ViewModel` is the business base class.
- `StateViewModel<State>` manages immutable state and emits state/general listeners.
- `ViewModelSpec` declares how to build a ViewModel and whether it is shared by `key`.
- `ViewModelBinding` is the scoped container used by Activity, Fragment, Compose, View, or plain classes.

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
    implementation("com.github.lwj1994:android_view_model:v0.1.1")
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

val counterSpec = viewModelSpec(key = "counter") {
    CounterViewModel()
}
```

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
    implementation("android_view_model:android-view-model:v0.1.1")
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

val counterSpec = viewModelSpec(key = "counter") {
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
    private val counter by lazy { viewModelBinding.watch(counterSpec) }
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
    private val counter = scope.viewModelBinding.read(counterSpec)

    fun increment() = counter.increment()

    override fun close() {
        scope.close()
    }
}
```

## Threading

The public ViewModel API is main-thread only. Core public classes/functions are annotated with `@MainThread`, and runtime assertions catch accidental calls from background threads.

Use `viewModelScope` for async work and hop back to the main thread before mutating state.

## Example

The `example` module demonstrates all supported host styles:

- Compose with `rememberRetainedViewModelBinding`
- Activity with `viewModelBinding`
- Fragment with `viewLifecycleViewModelBinding` and `activityViewModelBinding`
- Custom View with `viewModelBinding`
- Plain class with `ViewModelBindingScope`

Build it with:

```bash
gradle :example:assembleDebug
```

Run tests with:

```bash
gradle :android-view-model:testDebugUnitTest
```
