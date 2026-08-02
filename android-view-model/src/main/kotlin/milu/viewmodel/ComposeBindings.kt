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
 * Primary Compose resolution API for reactive access through a stable spec/factory.
 */
@Composable
@MainThread
public fun <VM : ViewModel> watchViewModel(
    factory: ViewModelFactory<VM>,
    binding: ViewModelBinding = currentViewModelBinding(),
    vararg keys: Any?,
): VM {
    val version = observeBindingUpdateVersion(binding)
    return remember(binding, factory, version, *keys) {
        binding.watch(factory)
    }
}

/**
 * Primary Compose resolution API for lifecycle-bound access without broad VM observation.
 */
@Composable
@MainThread
public fun <VM : ViewModel> readViewModel(
    factory: ViewModelFactory<VM>,
    binding: ViewModelBinding = currentViewModelBinding(),
    vararg keys: Any?,
): VM {
    // `read` ignores broad ViewModel/binding notifications. This dedicated
    // generation version changes only when one of the binding's handles is
    // disposed/recycled, allowing the stable spec to resolve a replacement.
    val version = observeBindingGenerationVersion(binding)
    return remember(binding, factory, version, *keys) {
        binding.read(factory)
    }
}

@Composable
@MainThread
public fun <State, VM : StateViewModel<State>> watchViewModelState(
    factory: ViewModelFactory<VM>,
    binding: ViewModelBinding = currentViewModelBinding(),
    vararg keys: Any?,
): State {
    return watchViewModel(factory, binding, *keys).state
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
    val viewModel = readViewModel(factory, binding, *keys)
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
