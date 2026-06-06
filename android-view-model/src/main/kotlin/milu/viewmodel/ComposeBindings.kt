package milu.viewmodel

import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

@Composable
@MainThread
public fun <VM : ViewModel> watchViewModel(
    factory: ViewModelFactory<VM>,
    binding: ViewModelBinding = currentViewModelBinding(),
    vararg keys: Any?,
): VM {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(binding) {
        val remove = binding.addUpdateListener { version += 1 }
        onDispose { remove() }
    }
    @Suppress("UNUSED_VARIABLE")
    val observedVersion = version
    val rememberKeys = arrayOf(factory, *keys)
    return remember(binding, *rememberKeys) {
        binding.watch(factory)
    }
}

@Composable
@MainThread
public fun <VM : ViewModel> readViewModel(
    factory: ViewModelFactory<VM>,
    binding: ViewModelBinding = currentViewModelBinding(),
    vararg keys: Any?,
): VM {
    val rememberKeys = arrayOf(factory, *keys)
    return remember(binding, *rememberKeys) {
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

@Composable
@MainThread
public fun <VM : ViewModel> ViewModelBuilder(
    factory: ViewModelFactory<VM>,
    binding: ViewModelBinding = currentViewModelBinding(),
    vararg keys: Any?,
    content: @Composable (VM) -> Unit,
) {
    content(watchViewModel(factory, binding, *keys))
}
