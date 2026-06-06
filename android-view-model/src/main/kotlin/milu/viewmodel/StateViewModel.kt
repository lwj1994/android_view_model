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
    ): () -> Unit {
        assertMainThread()
        return listenState { previous, current ->
            val previousSelected = previous?.let(selector)
            val currentSelected = selector(current)
            if (previousSelected != currentSelected) {
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
        if (isSameState(state, newState)) return
        previousState = state
        state = newState

        val snapshot = stateListeners.values.toList()
        snapshot.forEach { listener ->
            try {
                listener(previousState, state)
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
