package milu.viewmodel

import androidx.annotation.MainThread
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val processStateStore: ProcessStateStore<State>? = null,
) : ViewModel(coroutineContext) {
    public var state: State = initialState
        private set

    public var previousState: State? = null
        private set

    public val initialState: State = initialState

    private val stateListeners = linkedMapOf<String, (State?, State) -> Unit>()
    private val processStateSourceId = UUID.randomUUID().toString()
    private val processStateWriteMutex = Mutex()
    private var processStateVersion: Long = 0
    private var processStateOwnerId: String = ""
    private var isApplyingProcessState: Boolean = false

    init {
        startProcessStateSync()
    }

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
        publishProcessState(state)
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

    private fun startProcessStateSync() {
        val store = processStateStore ?: return
        viewModelScope.launch {
            store.observe()
                .catch { error ->
                    reportViewModelError(error, ErrorType.Listener, "process state observe error")
                }
                .collect { record ->
                    applyProcessState(record)
                }
        }
    }

    private fun applyProcessState(record: ProcessStateRecord<State>) {
        assertMainThread()
        if (!shouldAcceptProcessState(record)) return
        processStateVersion = record.version
        processStateOwnerId = record.sourceId
        if (isSameState(state, record.state)) return
        isApplyingProcessState = true
        try {
            setState(record.state)
        } finally {
            isApplyingProcessState = false
        }
    }

    private fun shouldAcceptProcessState(record: ProcessStateRecord<State>): Boolean {
        if (record.sourceId == processStateSourceId) return false
        if (record.version < processStateVersion) return false
        if (record.version == processStateVersion && record.sourceId <= processStateOwnerId) {
            return false
        }
        return true
    }

    private fun publishProcessState(newState: State) {
        val store = processStateStore ?: return
        if (isApplyingProcessState) return

        processStateVersion += 1
        processStateOwnerId = processStateSourceId
        val record = ProcessStateRecord(
            state = newState,
            version = processStateVersion,
            sourceId = processStateSourceId,
        )
        viewModelScope.launch {
            processStateWriteMutex.withLock {
                try {
                    store.write(record)
                } catch (error: Throwable) {
                    reportViewModelError(error, ErrorType.Lifecycle, "process state write error")
                }
            }
        }
    }
}

private data class StateTransition<State>(
    val previous: State,
    val current: State,
)
