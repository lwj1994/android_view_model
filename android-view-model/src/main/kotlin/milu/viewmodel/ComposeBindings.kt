package milu.viewmodel

import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

public val LocalViewModelBinding: ProvidableCompositionLocal<ViewModelBinding?> =
    staticCompositionLocalOf { null }

/**
 * Creates a binding that is disposed with the current composition.
 */
@Composable
@MainThread
public fun rememberViewModelBinding(): ViewModelBinding {
    val binding = remember { ViewModelBinding() }
    DisposableEffect(binding) {
        onDispose { binding.dispose() }
    }
    return binding
}

/**
 * Uses the current ViewModelStoreOwner binding when available, otherwise falls back to composition scope.
 */
@Composable
@MainThread
public fun rememberRetainedViewModelBinding(): ViewModelBinding {
    val owner = LocalViewModelStoreOwner.current
    return if (owner != null) {
        remember(owner) { owner.viewModelBinding }
    } else {
        rememberViewModelBinding()
    }
}

@Composable
@MainThread
public fun currentViewModelBinding(): ViewModelBinding {
    return LocalViewModelBinding.current ?: rememberViewModelBinding()
}

@Composable
@MainThread
public fun ViewModelBindingProvider(
    binding: ViewModelBinding = rememberViewModelBinding(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalViewModelBinding provides binding) {
        content()
    }
}

/**
 * Compose delegate entry point: use `val vm by watchViewModel(spec)`.
 * Subscribes during composition; every property access, including callbacks, resolves the current generation.
 *
 * A [ViewModel.notifyListeners] call invalidates the composable scope that calls this
 * function. Observation does not travel with a ViewModel resolved by the delegate: under
 * Compose strong skipping, a child composable that receives the same ViewModel instance
 * may be skipped. Never pass a ViewModel instance as a child composable parameter. Pass
 * immutable render values and event callbacks instead, or let the consuming composable
 * resolve the stable spec and call `watchViewModel` itself.
 */
@Composable
@MainThread
public fun <VM : ViewModel> watchViewModel(
    factory: ViewModelFactory<VM>,
    binding: ViewModelBinding = currentViewModelBinding(),
    vararg keys: Any?,
): ViewModelDelegate<VM> {
    val version = observeBindingUpdateVersion(binding)
    return remember(binding, factory, version, *keys) {
        binding.watch(factory)
        watchViewModel(factory) { binding }
    }
}

/**
 * Compose read delegate entry point: use `val vm by readViewModel(spec)`.
 * Observes generation disposal without VM notifications; resolves on every property access.
 */
@Composable
@MainThread
public fun <VM : ViewModel> readViewModel(
    factory: ViewModelFactory<VM>,
    binding: ViewModelBinding = currentViewModelBinding(),
    vararg keys: Any?,
): ViewModelDelegate<VM> {
    // `read` ignores broad ViewModel/binding notifications. This dedicated
    // generation version changes only when one of the binding's handles is
    // disposed/recycled, allowing the stable spec to resolve a replacement.
    val version = observeBindingGenerationVersion(binding)
    return remember(binding, factory, version, *keys) {
        binding.read(factory)
        readViewModel(factory) { binding }
    }
}

@Composable
@MainThread
public fun <State, VM : StateViewModel<State>> watchViewModelState(
    factory: ViewModelFactory<VM>,
    binding: ViewModelBinding = currentViewModelBinding(),
    vararg keys: Any?,
): State {
    val viewModel by watchViewModel(factory, binding, *keys)
    return viewModel.state
}

/**
 * Resolves a [StateViewModel] through read-style ownership and recomposes only
 * when the strongly typed [selector] result changes.
 *
 * Equality follows [StateViewModel.listenStateSelect]: local [equals], then
 * [ViewModelConfig.equals], then Kotlin `==`.
 */
@Composable
@MainThread
public fun <State, Selected, VM : StateViewModel<State>> selectViewModelState(
    factory: ViewModelFactory<VM>,
    selector: (State) -> Selected,
    equals: ((Selected, Selected) -> Boolean)? = null,
    binding: ViewModelBinding = currentViewModelBinding(),
    vararg keys: Any?,
): Selected {
    val resolved by readViewModel(factory, binding, *keys)
    // The effect must subscribe to this composition's generation and clean up that same instance.
    val viewModel = resolved
    var selected by remember(viewModel, selector, equals, *keys) {
        mutableStateOf(
            value = selector(viewModel.state),
            policy = neverEqualPolicy(),
        )
    }
    DisposableEffect(viewModel, selector, equals, *keys) {
        val remove = viewModel.listenStateSelect(
            selector = selector,
            equals = equals,
            onChanged = { _, current -> selected = current },
        )
        onDispose { remove() }
    }
    return selected
}

@Composable
private fun observeBindingUpdateVersion(binding: ViewModelBinding): Int {
    var version by remember(binding) { mutableIntStateOf(0) }
    DisposableEffect(binding) {
        val remove = binding.addUpdateListener { version += 1 }
        onDispose { remove() }
    }
    return version
}

@Composable
private fun observeBindingGenerationVersion(binding: ViewModelBinding): Int {
    var version by remember(binding) { mutableIntStateOf(0) }
    DisposableEffect(binding) {
        val remove = binding.addGenerationChangeListener { version += 1 }
        onDispose { remove() }
    }
    return version
}
