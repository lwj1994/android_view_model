package milu.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessStateBoundaryTest {
    @Before fun setUp() = ViewModel.reset()
    @After fun tearDown() = ViewModel.reset()

    @Test fun staleAndEqualVersionRecordsUseDeterministicSourceOrdering() = runTest {
        val records = MutableSharedFlow<ProcessStateRecord<Int>>(extraBufferCapacity = 16)
        val store = object : ProcessStateStore<Int> {
            override suspend fun read(): ProcessStateRecord<Int>? = null
            override fun observe() = records
            override suspend fun write(record: ProcessStateRecord<Int>) { records.emit(record) }
        }
        val spec = viewModelSpec {
            StateViewModel(0, coroutineContext = UnconfinedTestDispatcher(testScheduler), processStateStore = store)
        }
        val owner = ViewModelBinding()
        val vm by owner.readViewModel(spec)
        try {
            assertEquals(0, vm.state)
            records.emit(ProcessStateRecord(1, 10, "b"))
            records.emit(ProcessStateRecord(2, 9, "z"))
            records.emit(ProcessStateRecord(3, 10, "a"))
            records.emit(ProcessStateRecord(4, 10, "b"))
            assertEquals(1, vm.state)
            records.emit(ProcessStateRecord(5, 10, "c"))
            assertEquals(5, vm.state)
            vm.setState(6)
            assertEquals(6, vm.state)
            assertEquals(5, vm.previousState)
        } finally { owner.dispose() }
    }

    @Test fun equalVersionRemoteWinnerSuppressesQueuedLocalWrite() = runTest {
        val records = MutableSharedFlow<ProcessStateRecord<Int>>(extraBufferCapacity = 16)
        val writes = mutableListOf<ProcessStateRecord<Int>>()
        val store = object : ProcessStateStore<Int> {
            override suspend fun read(): ProcessStateRecord<Int>? = null
            override fun observe() = records
            override suspend fun write(record: ProcessStateRecord<Int>) { writes += record }
        }
        val spec = viewModelSpec {
            StateViewModel(0, coroutineContext = StandardTestDispatcher(testScheduler), processStateStore = store)
        }
        val owner = ViewModelBinding()
        val vm by owner.readViewModel(spec)
        try {
            assertEquals(0, vm.state)
            advanceUntilIdle()
            // Queue delivery before the write, with the same next local version.
            records.emit(ProcessStateRecord(2, 1, "~remote"))
            vm.setState(1)
            advanceUntilIdle()
            assertEquals(2, vm.state)
            assertTrue(writes.isEmpty())
        } finally { owner.dispose() }
    }

    @Test fun failedWriteIsReportedAndCancelledObservationIsNotReported() = runTest {
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(ViewModelConfig(onError = { _, type -> errors += type }))
        val store = object : ProcessStateStore<Int> {
            override suspend fun read(): ProcessStateRecord<Int>? = null
            override fun observe(): Flow<ProcessStateRecord<Int>> = flow { throw CancellationException("observation cancelled") }
            override suspend fun write(record: ProcessStateRecord<Int>) = error("write failed")
        }
        val spec = viewModelSpec {
            StateViewModel(0, coroutineContext = StandardTestDispatcher(testScheduler), processStateStore = store)
        }
        val owner = ViewModelBinding()
        val vm by owner.readViewModel(spec)
        try {
            assertEquals(0, vm.state)
            advanceUntilIdle()
            assertTrue(errors.isEmpty())
            vm.setState(1)
            advanceUntilIdle()
            assertEquals(1, vm.state)
            assertEquals(listOf(ErrorType.Lifecycle), errors)
        } finally { owner.dispose() }
    }
}
