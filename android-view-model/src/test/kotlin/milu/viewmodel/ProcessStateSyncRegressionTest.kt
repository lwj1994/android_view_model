package milu.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessStateSyncRegressionTest {
    @Before
    fun setUp() {
        ViewModel.reset()
    }

    @After
    fun tearDown() {
        ViewModel.reset()
    }

    @Test
    fun remoteStateListenerCorrection_isPublishedAndReachesOtherInstances() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = RecordingProcessStateStore<Int>()
        val spec = viewModelSpec {
            StateViewModel(0, coroutineContext = dispatcher, processStateStore = store)
        }
        val firstBinding = ViewModelBinding()
        val secondBinding = ViewModelBinding()
        val first by firstBinding.readViewModel(spec)
        val second by secondBinding.readViewModel(spec)
        try {
            assertNotSame(first, second)
            val firstEvents = mutableListOf<Pair<Int, Int>>()
            val secondEvents = mutableListOf<Pair<Int, Int>>()
            first.listenState { _, current ->
                if (current == 1) first.setState(2)
            }
            first.listenState { previous, current -> firstEvents += previous to current }
            second.listenState { previous, current -> secondEvents += previous to current }
            advanceUntilIdle()

            store.emitRemote(ProcessStateRecord(state = 1, version = 5, sourceId = "remote"))
            advanceUntilIdle()

            assertEquals(2, first.state)
            assertEquals(2, second.state)
            assertEquals(listOf(0 to 1, 1 to 2), firstEvents)
            assertEquals(firstEvents, secondEvents)
            val correction = store.writes.single()
            assertEquals(2, correction.state)
            assertEquals(6L, correction.version)
            assertTrue(correction.sourceId != "remote")
            assertEquals(correction, store.read())
        } finally {
            secondBinding.dispose()
            firstBinding.dispose()
        }
    }

    @Test
    fun remoteBroadListenerCorrection_isPublishedWithoutEchoingRemoteState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = RecordingProcessStateStore<Int>()
        val spec = viewModelSpec {
            StateViewModel(0, coroutineContext = dispatcher, processStateStore = store)
        }
        val binding = ViewModelBinding()
        val viewModel by binding.readViewModel(spec)
        try {
            val notifications = mutableListOf<Int>()
            binding.listen(spec) {
                notifications += viewModel.state
                if (viewModel.state == 1) viewModel.setState(2)
            }
            advanceUntilIdle()

            store.emitRemote(ProcessStateRecord(state = 1, version = 7, sourceId = "remote"))
            advanceUntilIdle()

            assertEquals(listOf(1, 2), notifications)
            assertEquals(2, viewModel.state)
            assertEquals(2, store.writes.single().state)
            assertEquals(8L, store.writes.single().version)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun queuedLocalSnapshots_coalesceWhileBusinessTransitionsRemainComplete() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = RecordingProcessStateStore<Int>()
        val spec = viewModelSpec {
            StateViewModel(0, coroutineContext = dispatcher, processStateStore = store)
        }
        val binding = ViewModelBinding()
        val viewModel by binding.readViewModel(spec)
        try {
            val events = mutableListOf<Pair<Int, Int>>()
            var broadNotifications = 0
            viewModel.listenState { _, current ->
                if (current == 1) viewModel.setState(2)
            }
            viewModel.listenState { previous, current -> events += previous to current }
            viewModel.listen { broadNotifications += 1 }
            advanceUntilIdle()

            viewModel.setState(1)
            advanceUntilIdle()

            assertEquals(listOf(0 to 1, 1 to 2), events)
            assertEquals(2, broadNotifications)
            assertEquals(2, viewModel.state)
            assertEquals(2, store.writes.single().state)
            // Versions belong to accepted local transitions, including a superseded snapshot.
            assertEquals(2L, store.writes.single().version)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun newerRemoteStateDuringLocalDelivery_supersedesTheOlderLocalSnapshot() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val store = RecordingProcessStateStore<Int>()
        val spec = viewModelSpec {
            StateViewModel(0, coroutineContext = dispatcher, processStateStore = store)
        }
        val binding = ViewModelBinding()
        val viewModel by binding.readViewModel(spec)
        try {
            val events = mutableListOf<Pair<Int, Int>>()
            var stateAfterRemoteEmission = -1
            val remote = ProcessStateRecord(state = 3, version = 100, sourceId = "remote")
            viewModel.listenState { _, current ->
                if (current == 1) {
                    store.emitRemote(remote)
                    stateAfterRemoteEmission = viewModel.state
                }
            }
            viewModel.listenState { previous, current -> events += previous to current }

            viewModel.setState(1)
            advanceUntilIdle()

            // Prove that remote application happened inside the local callback, before its drain
            // could publish. The older local value must never be assigned version 101 afterward.
            assertEquals(3, stateAfterRemoteEmission)
            assertEquals(listOf(0 to 1, 1 to 3), events)
            assertEquals(3, viewModel.state)
            assertEquals(remote, store.read())
            assertTrue(store.writes.isEmpty())

            viewModel.setState(4)
            advanceUntilIdle()

            assertEquals(4, store.writes.single().state)
            assertEquals(101L, store.writes.single().version)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun snapshotSupersededWhileWaitingForWriteMutex_isSkippedAfterLockAcquisition() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val store = RecordingProcessStateStore<Int>(beforeWrite = { record ->
            if (record.state == 1) releaseFirstWrite.await()
        })
        val spec = viewModelSpec {
            StateViewModel(0, coroutineContext = dispatcher, processStateStore = store)
        }
        val binding = ViewModelBinding()
        val viewModel by binding.readViewModel(spec)
        try {
            viewModel.setState(1)
            assertEquals(listOf(1), store.writes.map { it.state })
            viewModel.setState(2)
            assertEquals(listOf(1), store.writes.map { it.state })

            val remote = ProcessStateRecord(state = 3, version = 100, sourceId = "remote")
            store.emitRemote(remote)
            assertEquals(3, viewModel.state)
            releaseFirstWrite.complete(Unit)
            advanceUntilIdle()

            // Write 1 was already sent and is rejected by the store's version check. Write 2
            // was waiting in this ViewModel and must be skipped before reaching the store at all.
            assertEquals(listOf(1), store.writes.map { it.state })
            assertEquals(remote, store.read())
            assertEquals(3, viewModel.state)
        } finally {
            releaseFirstWrite.complete(Unit)
            binding.dispose()
        }
    }

    @Test
    fun remoteLocalComparatorFailure_isReportedOnceAndObservationContinues() = runTest {
        assertRemoteComparatorRecovery(useGlobalComparator = false)
    }

    @Test
    fun remoteGlobalComparatorFailure_isReportedOnceAndObservationContinues() = runTest {
        assertRemoteComparatorRecovery(useGlobalComparator = true)
    }

    @Test
    fun synchronousObserveFailure_usesTheErrorPipelineAndLeavesLocalWritesAvailable() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val failure = IllegalStateException("could not create observation flow")
        val reports = mutableListOf<Pair<Throwable, ErrorType>>()
        val uncaught = mutableListOf<Throwable>()
        val writes = mutableListOf<ProcessStateRecord<Int>>()
        ViewModel.initialize(
            config = ViewModelConfig(onError = { error, type -> reports += error to type }),
        )
        val store = object : ProcessStateStore<Int> {
            override suspend fun read(): ProcessStateRecord<Int>? = null

            override suspend fun write(record: ProcessStateRecord<Int>) {
                writes += record
            }

            override fun observe(): Flow<ProcessStateRecord<Int>> = throw failure
        }
        val spec = viewModelSpec {
            StateViewModel(
                0,
                coroutineContext = dispatcher + CoroutineExceptionHandler { _, error -> uncaught += error },
                processStateStore = store,
            )
        }
        val binding = ViewModelBinding()
        val viewModel by binding.readViewModel(spec)
        try {
            assertEquals(0, viewModel.state)
            advanceUntilIdle()

            assertEquals(listOf(failure to ErrorType.Listener), reports)
            assertTrue(uncaught.isEmpty())

            viewModel.setState(1)
            advanceUntilIdle()

            assertEquals(1, writes.single().state)
            assertEquals(listOf(failure to ErrorType.Listener), reports)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun cancelledProcessWrite_isNotReportedAndDoesNotStopLaterWrites() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val reports = mutableListOf<Throwable>()
        ViewModel.initialize(config = ViewModelConfig(onError = { error, _ -> reports += error }))
        val store = RecordingProcessStateStore<Int>(beforeWrite = { record ->
            if (record.state == 1) throw CancellationException("write cancelled")
        })
        val spec = viewModelSpec {
            StateViewModel(0, coroutineContext = dispatcher, processStateStore = store)
        }
        val binding = ViewModelBinding()
        val viewModel by binding.readViewModel(spec)
        try {
            viewModel.setState(1)
            advanceUntilIdle()
            assertTrue(reports.isEmpty())
            assertNull(store.read())

            viewModel.setState(2)
            advanceUntilIdle()

            assertTrue(reports.isEmpty())
            assertEquals(listOf(1, 2), store.writes.map { it.state })
            assertEquals(2, store.read()?.state)
        } finally {
            binding.dispose()
        }
    }

    private suspend fun TestScope.assertRemoteComparatorRecovery(useGlobalComparator: Boolean) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = RecordingProcessStateStore<Int>()
        val failure = IllegalStateException("remote equality failed")
        val comparisons = mutableListOf<Pair<Int, Int>>()
        val reports = mutableListOf<Pair<Throwable, ErrorType>>()
        val comparator: (Int, Int) -> Boolean = { previous, current ->
            comparisons += previous to current
            if (current == 1) throw failure
            previous == current
        }
        val globalComparator: ((Any?, Any?) -> Boolean)? = if (useGlobalComparator) {
            { previous, current -> comparator(previous as Int, current as Int) }
        } else {
            null
        }
        ViewModel.initialize(
            config = ViewModelConfig(
                equals = globalComparator,
                onError = { error, type -> reports += error to type },
            ),
        )
        val spec = viewModelSpec {
            StateViewModel(
                0,
                equals = if (useGlobalComparator) null else comparator,
                coroutineContext = dispatcher,
                processStateStore = store,
            )
        }
        val binding = ViewModelBinding()
        val viewModel by binding.readViewModel(spec)
        try {
            val events = mutableListOf<Pair<Int, Int>>()
            viewModel.listenState { previous, current -> events += previous to current }
            advanceUntilIdle()

            store.emitRemote(ProcessStateRecord(state = 1, version = 1, sourceId = "remote"))
            advanceUntilIdle()

            assertEquals(0, viewModel.state)
            assertNull(viewModel.previousState)
            assertTrue(events.isEmpty())
            assertEquals(listOf(failure to ErrorType.Listener), reports)
            assertEquals(listOf(0 to 1), comparisons)

            store.emitRemote(ProcessStateRecord(state = 2, version = 2, sourceId = "remote"))
            advanceUntilIdle()

            assertEquals(2, viewModel.state)
            assertEquals(listOf(0 to 2), events)
            assertEquals(listOf(0 to 1, 0 to 2), comparisons)
            assertEquals(listOf(failure to ErrorType.Listener), reports)
            assertTrue(store.writes.isEmpty())

            viewModel.setState(3)
            advanceUntilIdle()
            assertEquals(3L, store.writes.single().version)
        } finally {
            binding.dispose()
        }
    }
}

private class RecordingProcessStateStore<State>(
    private val beforeWrite: suspend (ProcessStateRecord<State>) -> Unit = {},
) : ProcessStateStore<State> {
    val writes = mutableListOf<ProcessStateRecord<State>>()
    private var current: ProcessStateRecord<State>? = null
    private val records = MutableSharedFlow<ProcessStateRecord<State>>(
        replay = 1,
        extraBufferCapacity = 16,
    )

    override suspend fun read(): ProcessStateRecord<State>? = current

    override suspend fun write(record: ProcessStateRecord<State>) {
        writes += record
        beforeWrite(record)
        accept(record)
    }

    override fun observe(): Flow<ProcessStateRecord<State>> = records

    fun emitRemote(record: ProcessStateRecord<State>) {
        accept(record)
    }

    private fun accept(record: ProcessStateRecord<State>) {
        val previous = current
        if (previous != null) {
            if (record.version < previous.version) return
            if (record.version == previous.version && record.sourceId <= previous.sourceId) return
        }
        current = record
        check(records.tryEmit(record))
    }
}
