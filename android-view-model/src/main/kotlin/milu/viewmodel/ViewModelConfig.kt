package milu.viewmodel

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Global configuration. Install once from Application.onCreate via [ViewModel.initialize].
 */
public data class ViewModelConfig(
    val isLoggingEnabled: Boolean = false,
    val equals: ((Any?, Any?) -> Boolean)? = null,
    val onError: ((Throwable, ErrorType) -> Unit)? = null,
)

internal object ViewModelGlobalConfig {
    private val currentRef = AtomicReference(ViewModelConfig())

    val current: ViewModelConfig
        get() = currentRef.get()

    fun set(config: ViewModelConfig) {
        currentRef.set(config)
    }

    fun reset() {
        currentRef.set(ViewModelConfig())
    }
}

internal object ViewModelLifecycleRegistry {
    val lifecycles = CopyOnWriteArrayList<ViewModelLifecycle>()
}

public fun viewModelLog(message: () -> String) {
    if (!ViewModel.config.isLoggingEnabled) return
    Log.d("AndroidViewModel", message())
}

public fun reportViewModelError(
    error: Throwable,
    type: ErrorType,
    context: String,
) {
    val handler = ViewModel.config.onError
    if (handler != null) {
        handler(error, type)
    } else {
        Log.e("AndroidViewModel", "[$type] $context", error)
    }
}
