package milu.viewmodel

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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

    private val stateListeners = linkedMapOf<String, (State, State) -> Unit>()
    private val pendingStateTransitions = ArrayDeque<StateTransition<State>>()
    private var isDispatchingStateTransitions = false
    private val processStateSourceId = UUID.randomUUID().toString()
    private val processStateWriteMutex = Mutex()
    private var processStateVersion: Long = 0
    private var processStateOwnerId: String = ""

    init {
        startProcessStateSync()
    }

    /**
     * Observes actual state transitions, with both values preserving [State]'s nullability.
     * Registering a listener does not emit an initial callback.
     */
    public fun listenState(onChanged: (State, State) -> Unit): () -> Unit {
        assertMainThread()
        val id = UUID.randomUUID().toString()
        stateListeners[id] = onChanged
        return { stateListeners.remove(id) }
    }

    public fun <R> listenStateSelect(
        selector: (State) -> R,
        onChanged: (R, R) -> Unit,
    ): () -> Unit = listenStateSelect(
        selector = selector,
        equals = null,
        onChanged = onChanged,
    )

    public fun <R> listenStateSelect(
        selector: (State) -> R,
        equals: ((R, R) -> Boolean)?,
        onChanged: (R, R) -> Unit,
    ): () -> Unit {
        assertMainThread()
        val globalEquals = ViewModel.config.equals
        val effectiveEquals: (R, R) -> Boolean = equals ?: if (globalEquals == null) {
            { previous, current -> previous == current }
        } else {
            { previous, current -> globalEquals(previous, current) }
        }
        return listenState { previous, current ->
            val previousSelected = selector(previous)
            val currentSelected = selector(current)
            if (!effectiveEquals(previousSelected, currentSelected)) {
                onChanged(previousSelected, currentSelected)
            }
        }
    }

    public fun setState(newState: State) {
        setStateInternal(newState, publishToProcessStore = true)
    }

    private fun setStateInternal(
        newState: State,
        publishToProcessStore: Boolean,
    ) {
        assertMainThread()
        if (isDisposed) {
            viewModelLog { "${this::class.qualifiedName}: setState after disposed" }
            return
        }
        val previous = state
        val isSame = try {
            isSameState(previous, newState)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            reportViewModelError(error, ErrorType.Listener, "${this::class.qualifiedName} setState error")
            return
        }
        if (isSame) return
        previousState = previous
        state = newState
        // Capture origin and version when the state is committed. A listener can accept a newer
        // remote record or create another local transition before this event finishes dispatching.
        val processRecord = if (publishToProcessStore) {
            createLocalProcessStateRecord(newState)
        } else {
            null
        }
        pendingStateTransitions.addLast(StateTransition(previous, newState, processRecord))
        dispatchStateTransitions()
    }

    private fun dispatchStateTransitions() {
        if (isDispatchingStateTransitions) return
        isDispatchingStateTransitions = true
        try {
            // Reentrant events reach every remaining listener in transition order. Share one
            // transaction for the complete drain so root refreshes coalesce without losing events.
            runInViewModelUpdateTransaction {
                while (pendingStateTransitions.isNotEmpty() && !isDisposed) {
                    val transition = pendingStateTransitions.removeFirst()
                    val snapshot = stateListeners.toList()
                    for ((id, listener) in snapshot) {
                        if (isDisposed) break
                        if (stateListeners[id] !== listener) continue
                        try {
                            listener(transition.previous, transition.current)
                        } catch (error: Throwable) {
                            reportViewModelError(error, ErrorType.Listener, "state listener error")
                        }
                    }
                    if (!isDisposed) {
                        notifyListeners()
                    }
                    if (!isDisposed) {
                        transition.processRecord?.let(::publishProcessState)
                    }
                }
            }
        } finally {
            isDispatchingStateTransitions = false
            pendingStateTransitions.clear()
        }
    }

    override fun onDispose(arg: InstanceArg) {
        assertMainThread()
        stateListeners.clear()
        pendingStateTransitions.clear()
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
            flow {
                // Obtaining the Flow can fail before collection starts, too.
                emitAll(store.observe())
            }
                .catch { error ->
                    if (error is CancellationException) throw error
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
        // Compare once through the same guarded path as local changes. Comparator failures are
        // reported for this record without terminating observation of later remote records.
        setStateInternal(record.state, publishToProcessStore = false)
    }

    private fun shouldAcceptProcessState(record: ProcessStateRecord<State>): Boolean {
        if (record.sourceId == processStateSourceId) return false
        if (record.version < processStateVersion) return false
        if (record.version == processStateVersion && record.sourceId <= processStateOwnerId) {
            return false
        }
        return true
    }

    private fun createLocalProcessStateRecord(newState: State): ProcessStateRecord<State>? {
        if (processStateStore == null) return null
        processStateVersion += 1
        processStateOwnerId = processStateSourceId
        return ProcessStateRecord(
            state = newState,
            version = processStateVersion,
            sourceId = processStateSourceId,
        )
    }

    private fun publishProcessState(record: ProcessStateRecord<State>) {
        val store = processStateStore ?: return
        viewModelScope.launch {
            processStateWriteMutex.withLock {
                // This boundary publishes snapshots: local transitions still reach all business
                // listeners, but an unsent snapshot superseded by any accepted state is skipped.
                // Check after taking the mutex because the record may age while another write runs.
                if (record.version != processStateVersion || record.sourceId != processStateOwnerId) {
                    return@withLock
                }
                try {
                    store.write(record)
                } catch (error: CancellationException) {
                    throw error
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
    val processRecord: ProcessStateRecord<State>?,
)
