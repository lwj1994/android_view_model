# Android ViewModel lint rules

Good and bad examples for every check in `android-view-model-lint`. The checks
ship inside the library AAR through `lintPublish`; see
[Recommended Android Lint checks](../README.md#recommended-android-lint-checks)
for setup. All four issues default to warning. Checks with an auto-fix show it
in Android Studio, and `./gradlew :app:lintFix` applies them in bulk.

| Issue ID | Catches |
| --- | --- |
| [`ViewModelDependencyCycle`](#viewmodeldependencycycle) | ViewModels that resolve each other |
| [`ViewModelCachedInstance`](#viewmodelcachedinstance) | A resolved ViewModel kept in a field or `by lazy` |
| [`ViewModelAliveForeverKey`](#viewmodelaliveforeverkey) | `aliveForever` without a key |
| [`ViewModelUnmanagedConstruction`](#viewmodelunmanagedconstruction) | A ViewModel constructed outside a builder |

## Shared declarations

The examples below use these declarations:

```kotlin
import milu.viewmodel.*

data class CounterState(val count: Int = 0)

class CounterViewModel : StateViewModel<CounterState>(CounterState()) {
    fun increment() = setState(state.copy(count = state.count + 1))
}

val counterSpec = viewModelSpec { CounterViewModel() }
```

## `ViewModelDependencyCycle`

Two or more ViewModels resolve each other through their own
`viewModelBinding`, by `read`/`watch` or by a `readViewModel`/`watchViewModel`
delegate. Resolving any of them can recurse until the runtime cycle check
throws.

**Bad**

```kotlin
val sessionSpec = viewModelSpec { SessionViewModel() }
val cartSpec = viewModelSpec { CartViewModel() }

class SessionViewModel : ViewModel() {
    val cart: CartViewModel by readViewModel(cartSpec) { viewModelBinding }
}

class CartViewModel : ViewModel() {
    val session: SessionViewModel get() = viewModelBinding.read(sessionSpec)
}
```

**Good**

Keep dependencies pointing one way. Move state that both sides need into the
lower-level module.

```kotlin
val sessionSpec = viewModelSpec { SessionViewModel() }
val cartSpec = viewModelSpec { CartViewModel() }

class SessionViewModel : ViewModel() {
    var userId: String? = null
}

class CartViewModel : ViewModel() {
    val session: SessionViewModel by readViewModel(sessionSpec) { viewModelBinding }
}
```

The check compares types. If keys make the instances distinct on purpose,
suppress the warning with a reason.

## `ViewModelCachedInstance`

A field, an assignment to a property, or a `by lazy` property keeps the result
of `read`/`watch`. After `recycle` or disposal, it still points at the old,
disposed generation.

Auto-fix: a `val` initialized by `read`/`watch`, or a `by lazy` property, becomes
a getter (`val x get() = …`). Assignments, such as to a `lateinit var`, need a
manual change.

**Bad**

```kotlin
class CounterController : AutoCloseable {
    private val scope = ViewModelBindingScope()
    private val counter = scope.viewModelBinding.read(counterSpec)
    private val observed by lazy { scope.viewModelBinding.watch(counterSpec) }

    fun increment() {
        counter.increment()
        observed.increment()
    }

    override fun close() = scope.close()
}

class CheckoutViewModel : ViewModel() {
    private lateinit var counter: CounterViewModel

    fun start() {
        counter = viewModelBinding.read(counterSpec)
    }
}
```

**Good**

Use a `readViewModel`/`watchViewModel` delegate, or a getter that resolves on
every access. A local variable inside one function is fine.

```kotlin
class CounterController : AutoCloseable {
    private val scope = ViewModelBindingScope()
    private val counter: CounterViewModel by readViewModel(counterSpec) { scope.viewModelBinding }

    fun increment() = counter.increment()

    override fun close() = scope.close()
}

class CheckoutViewModel : ViewModel() {
    private val counter: CounterViewModel get() = viewModelBinding.read(counterSpec)

    fun start() {
        val counter = viewModelBinding.watch(counterSpec)
        counter.increment()
        this.counter.increment()
    }
}
```

## `ViewModelAliveForeverKey`

`aliveForever = true` keeps an instance after its last owner leaves. Without a
key, nothing can find that instance again, and the runtime rejects it before
the builder runs.

Auto-fix: when `aliveForever` is a named argument, adds `key = "auth"` for
`authSpec`, or a key built from the arguments, such as `{ "userCache:$it" }`.

**Bad**

```kotlin
class AuthViewModel : ViewModel()
class UserViewModel(val userId: String) : ViewModel()

val authSpec = viewModelSpec(aliveForever = true) { AuthViewModel() }

val userCacheSpec = viewModelSpecWithArg<UserViewModel, String>(
    builder = ::UserViewModel,
    aliveForever = { true },
)
```

**Good**

```kotlin
class AuthViewModel : ViewModel()
class UserViewModel(val userId: String) : ViewModel()

val authSpec = viewModelSpec(key = "auth", aliveForever = true) { AuthViewModel() }

val userCacheSpec = viewModelSpecWithArg<UserViewModel, String>(
    builder = ::UserViewModel,
    key = { userId -> "user:$userId" },
    aliveForever = { true },
)
```

To share one instance between screens, a key alone is usually enough. The
instance is disposed after the last screen leaves, so `aliveForever` is not
needed.

## `ViewModelUnmanagedConstruction`

A ViewModel is constructed outside a spec builder or
`ViewModelFactory.build`. No binding owns it, so it never gets lifecycle
callbacks or automatic disposal.

**Bad**

```kotlin
fun startCounter() {
    val counter = CounterViewModel()
    counter.increment()
}
```

**Good**

Construct the ViewModel in a spec builder, as `counterSpec` does, or in
`ViewModelFactory.build`, and resolve it through a binding. This also applies
in tests.

```kotlin
fun startCounter() {
    ViewModelBindingScope().use { scope ->
        scope.viewModelBinding.read(counterSpec).increment()
    }
}

class CounterFactory : ViewModelFactory<CounterViewModel> {
    override val modelClass = CounterViewModel::class

    override fun build() = CounterViewModel()
}
```

## Suppressing a check

Fix the ownership or spec problem first. For an intentional exception,
suppress the narrowest scope and explain why:

```kotlin
@Suppress("ViewModelCachedInstance") // kept for the application lifetime
val appCounter = ViewModelBindingScope().viewModelBinding.read(counterSpec)
```

To turn a check off for a whole module, set its severity in `lint.xml`:

```xml
<lint>
    <issue id="ViewModelCachedInstance" severity="ignore" />
</lint>
```
