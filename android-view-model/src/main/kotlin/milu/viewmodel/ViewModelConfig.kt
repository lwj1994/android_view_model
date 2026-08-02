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
        try {
            handler(error, type)
        } catch (handlerError: Throwable) {
            safeViewModelErrorLog(
                message = "[$type] onError callback threw while reporting $context",
                error = handlerError,
            )
            safeViewModelErrorLog(
                message = "[$type] Original error from $context",
                error = error,
            )
        }
    } else {
        safeViewModelErrorLog("[$type] $context", error)
    }
}

private fun safeViewModelErrorLog(
    message: String,
    error: Throwable,
) {
    try {
        Log.e("AndroidViewModel", message, error)
    } catch (_: Throwable) {
        // android.util.Log is unavailable in some local JVM test environments.
        // Error reporting must never break the listener or disposal chain.
    }
}
