package milu.viewmodel

import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.IdentityHashMap

/**
 * Pause/resume source for a binding.
 */
@MainThread
public interface ViewModelBindingPauseProvider {
    public val isPaused: Boolean

    public fun setOnPauseChanged(listener: (Boolean) -> Unit)

    public fun dispose()
}

/**
 * Adds an independent subscription and returns an idempotent cancellation block.
 * Cancelling a subscription does not dispose the source.
 *
 * Older custom providers keep their existing interface implementation: one callback
 * installed through [ViewModelBindingPauseProvider.setOnPauseChanged] fans out to
 * their subscriptions. Such providers should not replace that callback themselves
 * while subscriptions are active. [BasePauseProvider] supports both APIs together.
 */
@MainThread
public fun ViewModelBindingPauseProvider.addOnPauseChanged(
    listener: (Boolean) -> Unit,
): () -> Unit {
    assertMainThread()
    return if (this is BasePauseProvider) {
        addOnPauseChanged(listener)
    } else {
        LegacyPauseProviderSubscriptions.add(this, listener)
    }
}

@MainThread
public open class BasePauseProvider : ViewModelBindingPauseProvider {
    private var listener: ((Boolean) -> Unit)? = null
    private val subscriptions = PauseChangeListeners()
    private var disposed = false

    final override var isPaused: Boolean = false
        private set

    override fun setOnPauseChanged(listener: (Boolean) -> Unit) {
        assertMainThread()
        if (disposed) return
        this.listener = listener
    }

    /** Adds a subscription independent of the legacy [setOnPauseChanged] callback. */
    public fun addOnPauseChanged(listener: (Boolean) -> Unit): () -> Unit {
        assertMainThread()
        if (disposed) {
            throw ViewModelError("Cannot subscribe to a disposed pause provider.")
        }
        return subscriptions.add(listener)
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
        listener?.let { notifyPauseListener(it, paused) }
        subscriptions.notify(paused)
    }

    override fun dispose() {
        assertMainThread()
        disposed = true
        listener = null
        subscriptions.clear()
    }
}

private fun notifyPauseListener(listener: (Boolean) -> Unit, paused: Boolean) {
    try {
        listener(paused)
    } catch (error: Throwable) {
        reportViewModelError(error, ErrorType.PauseResume, "pause provider listener error")
    }
}

private class PauseChangeListeners {
    private val listeners = linkedMapOf<Any, (Boolean) -> Unit>()

    val isEmpty: Boolean
        get() = listeners.isEmpty()

    fun add(listener: (Boolean) -> Unit): () -> Unit {
        val token = Any()
        listeners[token] = listener
        return {
            assertMainThread()
            listeners.remove(token)
            Unit
        }
    }

    fun notify(paused: Boolean) {
        listeners.toList().forEach { (token, listener) ->
            if (listeners[token] === listener) notifyPauseListener(listener, paused)
        }
    }

    fun clear() {
        listeners.clear()
    }
}

/** Keeps the original provider interface binary-compatible for custom implementations. */
private object LegacyPauseProviderSubscriptions {
    private val providers = IdentityHashMap<ViewModelBindingPauseProvider, PauseChangeListeners>()

    fun add(provider: ViewModelBindingPauseProvider, listener: (Boolean) -> Unit): () -> Unit {
        val previous = providers[provider]
        val subscriptions = previous ?: PauseChangeListeners().also { providers[provider] = it }
        val cancel = subscriptions.add(listener)
        if (previous == null) {
            try {
                provider.setOnPauseChanged(subscriptions::notify)
            } catch (error: Throwable) {
                cancel()
                if (subscriptions.isEmpty && providers[provider] === subscriptions) {
                    providers.remove(provider)
                }
                throw error
            }
        }
        var cancelled = false
        return {
            assertMainThread()
            if (!cancelled) {
                cancelled = true
                cancel()
                if (subscriptions.isEmpty && providers[provider] === subscriptions) {
                    providers.remove(provider)
                    // The old setter cannot accept null. A no-op releases the dispatcher
                    // and its subscriber references without ending the provider lifetime.
                    provider.setOnPauseChanged {}
                }
            }
        }
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
    private class Registration(var ownsProvider: Boolean) {
        var cancel: () -> Unit = {}
    }

    private val providers = IdentityHashMap<ViewModelBindingPauseProvider, Registration>()
    private var wasPaused = false
    private var disposed = false

    public val isPaused: Boolean
        get() = providers.keys.any { it.isPaused }

    /** Borrows a source; removal only cancels this controller's subscription. */
    public fun addProvider(provider: ViewModelBindingPauseProvider) {
        addProvider(provider, ownsProvider = false)
    }

    /** When [ownsProvider] is true, removal also disposes the source. */
    public fun addProvider(provider: ViewModelBindingPauseProvider, ownsProvider: Boolean) {
        assertMainThread()
        if (disposed) {
            throw ViewModelError("Cannot add a pause provider after controller disposal.")
        }
        providers[provider]?.let { registration ->
            registration.ownsProvider = registration.ownsProvider || ownsProvider
            return
        }
        val registration = Registration(ownsProvider)
        providers[provider] = registration
        val cancel = try {
            provider.addOnPauseChanged {
                // A custom provider may invoke its callback after removal or re-addition.
                if (providers[provider] === registration) notifyPauseChange()
            }
        } catch (error: Throwable) {
            if (providers[provider] === registration) {
                providers.remove(provider)
                releaseProvider(provider, registration)
            }
            throw error
        }
        if (providers[provider] === registration) {
            registration.cancel = cancel
        } else {
            // Subscription installation itself may synchronously remove this provider.
            cancel()
        }
        notifyPauseChange()
    }

    public fun removeProvider(provider: ViewModelBindingPauseProvider) {
        assertMainThread()
        val registration = providers.remove(provider) ?: return
        releaseProvider(provider, registration)
        notifyPauseChange()
    }

    public fun dispose() {
        assertMainThread()
        if (disposed) return
        disposed = true
        val snapshot = providers.entries.map { it.key to it.value }
        providers.clear()
        wasPaused = false
        snapshot.forEach { (provider, registration) -> releaseProvider(provider, registration) }
    }

    private fun releaseProvider(provider: ViewModelBindingPauseProvider, registration: Registration) {
        try {
            registration.cancel()
        } catch (error: Throwable) {
            reportViewModelError(error, ErrorType.PauseResume, "pause provider unsubscribe error")
        }
        if (registration.ownsProvider) {
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
