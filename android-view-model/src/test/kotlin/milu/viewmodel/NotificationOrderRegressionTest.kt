package milu.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationOrderRegressionTest {
    @Before
    fun setUp() {
        ViewModel.reset()
    }

    @After
    fun tearDown() {
        ViewModel.reset()
    }

    @Test
    fun reentrantStateListeners_deliverEveryTransitionAndRefreshRootOnce() {
        var updates = 0
        val binding = ViewModelBinding { updates += 1 }
        val spec = viewModelSpec { StateViewModel(0) }
        val viewModel by binding.watchViewModel(spec)
        try {
            val events = mutableListOf<Pair<Int, Int>>()
            val selected = mutableListOf<Int>()
            var broadNotifications = 0
            viewModel.listenState { _, current ->
                if (current == 1) viewModel.setState(2)
            }
            binding.listenState(spec) { previous, current -> events += previous to current }
            binding.listenStateSelect(spec, selector = { it }) { _, current -> selected += current }
            binding.listen(spec) { broadNotifications += 1 }

            viewModel.setState(1)

            assertEquals(listOf(0 to 1, 1 to 2), events)
            assertEquals(listOf(1, 2), selected)
            assertEquals(2, viewModel.state)
            assertEquals(2, broadNotifications)
            assertEquals(1, updates)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun reentrantBroadListener_coalescesBothRootsUntilTheDrainEnds() {
        var firstUpdates = 0
        var secondUpdates = 0
        val firstBinding = ViewModelBinding { firstUpdates += 1 }
        val secondBinding = ViewModelBinding { secondUpdates += 1 }
        val spec = viewModelSpec(key = "reentrant-shared-state") { StateViewModel(0) }
        val viewModel by firstBinding.watchViewModel(spec)
        val secondViewModel by secondBinding.watchViewModel(spec)
        try {
            assertSame(viewModel, secondViewModel)
            val events = mutableListOf<Pair<Int, Int>>()
            val selected = mutableListOf<Int>()
            val notifications = mutableListOf<Int>()
            firstBinding.listenState(spec) { previous, current -> events += previous to current }
            firstBinding.listenStateSelect(spec, selector = { it }) { _, current -> selected += current }
            firstBinding.listen(spec) {
                notifications += viewModel.state
                if (viewModel.state == 1) viewModel.setState(2)
            }

            viewModel.setState(1)

            assertEquals(listOf(0 to 1, 1 to 2), events)
            assertEquals(listOf(1, 2), selected)
            assertEquals(listOf(1, 2), notifications)
            assertEquals(1, firstUpdates)
            assertEquals(1, secondUpdates)

            // A subsequent public call starts a new transaction after the reentrant drain ends.
            viewModel.setState(3)

            assertEquals(listOf(0 to 1, 1 to 2, 2 to 3), events)
            assertEquals(listOf(1, 2, 3), selected)
            assertEquals(listOf(1, 2, 3), notifications)
            assertEquals(2, firstUpdates)
            assertEquals(2, secondUpdates)
        } finally {
            secondBinding.dispose()
            firstBinding.dispose()
        }
    }

    @Test
    fun asynchronousStateChanges_startANewRootTransaction() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var updates = 0
        val binding = ViewModelBinding { updates += 1 }
        val spec = viewModelSpec { StateViewModel(0, coroutineContext = dispatcher) }
        val viewModel by binding.watchViewModel(spec)
        try {
            val notifications = mutableListOf<Int>()
            binding.listen(spec) {
                notifications += viewModel.state
                if (viewModel.state == 1) {
                    viewModel.viewModelScope.launch { viewModel.setState(2) }
                }
            }

            viewModel.setState(1)

            assertEquals(1, viewModel.state)
            assertEquals(listOf(1), notifications)
            assertEquals(1, updates)

            advanceUntilIdle()

            assertEquals(2, viewModel.state)
            assertEquals(listOf(1, 2), notifications)
            assertEquals(2, updates)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun nullableReentrantStates_preserveBothSidesOfEveryTransition() {
        val binding = ViewModelBinding()
        val spec = viewModelSpec { StateViewModel<Int?>(null) }
        val viewModel by binding.readViewModel(spec)
        try {
            val events = mutableListOf<Pair<Int?, Int?>>()
            val selections = mutableListOf<Pair<Int?, Int?>>()
            viewModel.listenState { _, current ->
                if (current == 1) viewModel.setState(null)
            }
            binding.listenState(spec) { previous, current -> events += previous to current }
            binding.listenStateSelect(spec, selector = { it }) { previous, current ->
                selections += previous to current
            }

            viewModel.setState(1)

            assertEquals(listOf(null to 1, 1 to null), events)
            assertEquals(events, selections)
            assertEquals(1, viewModel.previousState)
            assertNull(viewModel.state)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun disposingDuringStateDelivery_dropsRemainingAndQueuedCallbacks() {
        var updates = 0
        val binding = ViewModelBinding { updates += 1 }
        val spec = viewModelSpec { StateViewModel(0) }
        val viewModel by binding.watchViewModel(spec)
        try {
            val original = viewModel
            val firstEvents = mutableListOf<Int>()
            val laterEvents = mutableListOf<Int>()
            var broadNotifications = 0
            viewModel.listenState { _, current ->
                firstEvents += current
                if (current == 1) {
                    viewModel.setState(2)
                    binding.dispose()
                }
            }
            viewModel.listenState { _, current -> laterEvents += current }
            viewModel.listenStateSelect(selector = { it }) { _, current -> laterEvents += current }
            viewModel.listen { broadNotifications += 1 }

            viewModel.setState(1)

            assertTrue(original.isDisposed)
            assertTrue(binding.isDisposed)
            assertEquals(listOf(1), firstEvents)
            assertTrue(laterEvents.isEmpty())
            assertEquals(0, broadNotifications)
            assertEquals(0, updates)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun listenerFailures_doNotInterruptQueuedTransitions() {
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(config = ViewModelConfig(onError = { _, type -> errors += type }))
        val binding = ViewModelBinding()
        val spec = viewModelSpec { StateViewModel(0) }
        val viewModel by binding.readViewModel(spec)
        try {
            val events = mutableListOf<Int>()
            viewModel.listenState { _, current ->
                if (current == 1) {
                    viewModel.setState(2)
                    error("first transition listener failed")
                }
            }
            viewModel.listenStateSelect(selector = { it }) { _, current ->
                if (current == 1) error("first transition selector failed")
            }
            viewModel.listenState { _, current -> events += current }

            viewModel.setState(1)

            assertEquals(listOf(1, 2), events)
            assertEquals(2, viewModel.state)
            assertEquals(listOf(ErrorType.Listener, ErrorType.Listener), errors)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun listenThroughWatchedDiamond_reachesFinalDerivedValue() {
        val sourceSpec = viewModelSpec(key = "notification-order-source") { StateViewModel(0) }
        val leftSpec = viewModelSpec(key = "notification-order-left") {
            NotificationBranchViewModel(sourceSpec)
        }
        val rightSpec = viewModelSpec(key = "notification-order-right") {
            NotificationBranchViewModel(sourceSpec)
        }
        val aggregateSpec = viewModelSpec { NotificationAggregateViewModel(leftSpec, rightSpec) }
        val totalSpec = viewModelSpec { NotificationTotalViewModel(aggregateSpec) }
        var updates = 0
        val binding = ViewModelBinding { updates += 1 }
        val source by binding.readViewModel(sourceSpec)
        val total by binding.watchViewModel(totalSpec)
        try {
            assertEquals(0, total.state)
            val totals = mutableListOf<Int>()
            binding.listenState(totalSpec) { _, current -> totals += current }

            source.setState(1)

            assertEquals(listOf(1, 2), totals)
            assertEquals(2, total.state)
            assertEquals(1, updates)
        } finally {
            binding.dispose()
        }
    }
}

private class NotificationBranchViewModel(
    sourceSpec: ViewModelFactory<StateViewModel<Int>>,
) : ViewModel() {
    private val source by readViewModel(sourceSpec) { viewModelBinding }
    var value = 0
        private set

    init {
        viewModelBinding.listen(sourceSpec) {
            value = source.state
            notifyListeners()
        }
    }
}

private class NotificationAggregateViewModel(
    leftSpec: ViewModelFactory<NotificationBranchViewModel>,
    rightSpec: ViewModelFactory<NotificationBranchViewModel>,
) : ViewModel() {
    private val left by watchViewModel(leftSpec) { viewModelBinding }
    private val right by watchViewModel(rightSpec) { viewModelBinding }

    val total: Int
        get() = left.value + right.value
}

private class NotificationTotalViewModel(
    aggregateSpec: ViewModelFactory<NotificationAggregateViewModel>,
) : StateViewModel<Int>(0) {
    private val aggregate by readViewModel(aggregateSpec) { viewModelBinding }

    init {
        viewModelBinding.listen(aggregateSpec) { setState(aggregate.total) }
        // Resolve both watched branches before the source sends its first update.
        setState(aggregate.total)
    }
}
