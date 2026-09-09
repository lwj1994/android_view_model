# AndroidViewModel examples

All examples declare VM properties with `by watchViewModel/readViewModel`.

- [MainActivity.kt](src/main/kotlin/milu/viewmodel/example/MainActivity.kt): Activity, Compose, Fragment views, Activity-shared bindings, and custom Views.
- [CounterModels.kt](src/main/kotlin/milu/viewmodel/example/CounterModels.kt): Nested ViewModels and plain-class binding scopes.
- [ProcessCounterExample.kt](src/main/kotlin/milu/viewmodel/example/ProcessCounterExample.kt): Cross-process state synchronization and Compose consumers.

In Compose:

```kotlin
val counter by watchViewModel(counterSpec)
ComposeButton(onClick = { counter.reset() }) {
    Text("Reset")
}
```

Hosts and nested ViewModels explicitly supply a binding lambda:

```kotlin
val counter by watchViewModel(counterSpec) { viewModelBinding }
val analytics by readViewModel(analyticsSpec) { viewModelBinding }
```

Fragment views use `{ viewLifecycleViewModelBinding }`; plain classes use
`{ scope.viewModelBinding }`. Delegates resolve the current instance on each
access. After recycle, keep accessing the same property.

Do not store VM instances or use `by lazy` or `remember { vm }`. Event callbacks
use `{ vm.action() }`, not `vm::action`, which captures an instance immediately.
Pass stable specs across components, and immutable render values and event
callbacks across composable boundaries.

Plain classes close their scope when finished. Other hosts release ownership
through their binding lifecycle.
