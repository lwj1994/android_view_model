package milu.viewmodel

import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * Pause/resume source for a binding.
 */
@MainThread
public interface ViewModelBindingPauseProvider {
    public val isPaused: Boolean

    public fun setOnPauseChanged(listener: (Boolean) -> Unit)

    public fun dispose()
}

@MainThread
public open class BasePauseProvider : ViewModelBindingPauseProvider {
    private var listener: ((Boolean) -> Unit)? = null
    private var disposed = false

    final override var isPaused: Boolean = false
        private set

    override fun setOnPauseChanged(listener: (Boolean) -> Unit) {
        assertMainThread()
        this.listener = listener
    }

    protected fun pause() {
        setPaused(true)
    }

    protected fun resume() {
        setPaused(false)
    }

    protected fun setPaused(paused: Boolean) {
        assertMainThread()
        if (disposed || isPaused == paused) return
        isPaused = paused
        listener?.invoke(paused)
    }

    override fun dispose() {
        assertMainThread()
        disposed = true
        listener = null
    }
}

/**
 * Pauses on Lifecycle STOP and resumes on START. Useful for Activity and Fragment visibility.
 */
@MainThread
public class LifecyclePauseProvider(
    owner: LifecycleOwner,
) : BasePauseProvider(), DefaultLifecycleObserver {
    private val lifecycle: Lifecycle = owner.lifecycle

    init {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            pause()
        }
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        resume()
    }

    override fun onStop(owner: LifecycleOwner) {
        pause()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        dispose()
    }

    override fun dispose() {
        lifecycle.removeObserver(this)
        super.dispose()
    }
}

/**
 * Pauses while any provider is paused. Callbacks describe aggregate state transitions,
 * not individual provider events; resuming one source cannot override another source.
 */
@MainThread
public class PauseAwareController(
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
) {
    private val providers = linkedSetOf<ViewModelBindingPauseProvider>()
    private var wasPaused = false
    private var disposed = false

    public val isPaused: Boolean
        get() = providers.any { it.isPaused }

    public fun addProvider(provider: ViewModelBindingPauseProvider) {
        assertMainThread()
        if (disposed) {
            throw ViewModelError("Cannot add a pause provider after controller disposal.")
        }
        if (!providers.add(provider)) return
        provider.setOnPauseChanged {
            // A custom provider may still invoke its callback after removal.
            if (provider in providers) notifyPauseChange()
        }
        notifyPauseChange()
    }

    public fun removeProvider(provider: ViewModelBindingPauseProvider) {
        assertMainThread()
        if (!providers.remove(provider)) return
        try {
            provider.dispose()
        } catch (error: Throwable) {
            reportViewModelError(error, ErrorType.PauseResume, "pause provider dispose error")
        }
        notifyPauseChange()
    }

    public fun dispose() {
        assertMainThread()
        if (disposed) return
        disposed = true
        val snapshot = providers.toList()
        providers.clear()
        wasPaused = false
        snapshot.forEach { provider ->
            try {
                provider.dispose()
            } catch (error: Throwable) {
                reportViewModelError(error, ErrorType.PauseResume, "pause provider dispose error")
            }
        }
    }

    private fun notifyPauseChange() {
        if (disposed) return
        val paused = isPaused
        if (wasPaused == paused) return
        // Commit the transition before calling user code, which may re-enter the controller.
        wasPaused = paused
        try {
            if (paused) onPause() else onResume()
        } catch (error: Throwable) {
            reportViewModelError(error, ErrorType.PauseResume, "pause/resume callback error")
        }
    }
}
