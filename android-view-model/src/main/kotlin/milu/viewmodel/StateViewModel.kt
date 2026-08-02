package milu.viewmodel

import androidx.annotation.MainThread
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * ViewModel base class for immutable state.
 */
@MainThread
public open class StateViewModel<State>(
    initialState: State,
    private val equals: ((State, State) -> Boolean)? = null,
    coroutineContext: CoroutineContext = kotlinx.coroutines.SupervisorJob() +
        kotlinx.coroutines.Dispatchers.Main.immediate,
) : ViewModel(coroutineContext) {
    public var state: State = initialState
        private set

    public var previousState: State? = null
        private set

    public val initialState: State = initialState

    private val stateListeners = linkedMapOf<String, (State?, State) -> Unit>()

    public fun listenState(onChanged: (State?, State) -> Unit): () -> Unit {
        assertMainThread()
        val id = UUID.randomUUID().toString()
        stateListeners[id] = onChanged
        return { stateListeners.remove(id) }
    }

    public fun <R> listenStateSelect(
        selector: (State) -> R,
        onChanged: (R?, R) -> Unit,
    ): () -> Unit = listenStateSelect(
        selector = selector,
        equals = null,
        onChanged = onChanged,
    )

    public fun <R> listenStateSelect(
        selector: (State) -> R,
        equals: ((R, R) -> Boolean)?,
        onChanged: (R?, R) -> Unit,
    ): () -> Unit {
        assertMainThread()
        val globalEquals = ViewModel.config.equals
        val effectiveEquals: (R, R) -> Boolean = equals ?: if (globalEquals == null) {
            { previous, current -> previous == current }
        } else {
            { previous, current -> globalEquals(previous, current) }
        }
        return listenState { previous, current ->
            // Every callback represents a real transition, so `previous` is
            // the actual former state. It may itself be null when State is nullable.
            @Suppress("UNCHECKED_CAST")
            val previousSelected = selector(previous as State)
            val currentSelected = selector(current)
            if (!effectiveEquals(previousSelected, currentSelected)) {
                onChanged(previousSelected, currentSelected)
            }
        }
    }

    public fun setState(newState: State) {
        assertMainThread()
        if (isDisposed) {
            viewModelLog { "${this::class.qualifiedName}: setState after disposed" }
            return
        }
        val transition = StateTransition(previous = state, current = newState)
        val isSame = try {
            isSameState(transition.previous, transition.current)
        } catch (error: Throwable) {
            reportViewModelError(error, ErrorType.Listener, "${this::class.qualifiedName} setState error")
            return
        }
        if (isSame) return
        previousState = transition.previous
        state = newState

        val snapshot = stateListeners.toList()
        snapshot.forEach { (id, listener) ->
            if (stateListeners[id] !== listener) return@forEach
            try {
                listener(transition.previous, transition.current)
            } catch (error: Throwable) {
                reportViewModelError(error, ErrorType.Listener, "state listener error")
            }
        }
        notifyListeners()
    }

    override fun onDispose(arg: InstanceArg) {
        assertMainThread()
        stateListeners.clear()
        super.onDispose(arg)
    }

    private fun isSameState(
        previous: State,
        current: State,
    ): Boolean {
        equals?.let { return it(previous, current) }
        ViewModel.config.equals?.let { return it(previous, current) }
        return previous === current
    }
}

private data class StateTransition<State>(
    val previous: State,
    val current: State,
)
