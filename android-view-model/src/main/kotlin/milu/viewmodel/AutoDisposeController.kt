package milu.viewmodel

import androidx.annotation.MainThread

/**
 * Collects cleanup blocks and runs them in registration order.
 */
@MainThread
public class AutoDisposeController {
    private val blocks = mutableListOf<() -> Unit>()
    private var disposed = false

    init {
        // A constructor can fail before the ViewModel receives its lifecycle handle.
        // Release already registered resources without invoking a partial subclass.
        registerViewModelConstructionRollback(::dispose)
    }

    public fun addDispose(block: () -> Unit) {
        assertMainThread()
        if (disposed) {
            block()
            return
        }
        blocks += block
    }

    public fun dispose() {
        assertMainThread()
        if (disposed) return
        disposed = true
        val snapshot = blocks.toList()
        blocks.clear()
        snapshot.forEach { block ->
            try {
                block()
            } catch (error: Throwable) {
                reportViewModelError(error, ErrorType.Dispose, "AutoDisposeController dispose error")
            }
        }
    }
}
