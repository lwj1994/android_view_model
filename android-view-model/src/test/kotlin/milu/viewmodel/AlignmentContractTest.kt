package milu.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlignmentContractTest {
    @Before
    fun setUp() {
        ViewModel.reset()
    }

    @After
    fun tearDown() {
        ViewModel.reset()
    }

    @Test
    fun update_notifiesAfterSuccessfulSyncAndAsyncWorkOnly() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val binding = ViewModelBinding()
        try {
            val spec = viewModelSpec { AlignmentViewModel() }
            val viewModel = binding.read(spec)
            var notifications = 0
            viewModel.listen { notifications += 1 }

            viewModel.update {}
            assertEquals(1, notifications)

            assertThrows(IllegalStateException::class.java) {
                viewModel.update { error("sync failure") }
            }
            assertEquals(1, notifications)

            viewModel.updateAsync {}
            assertEquals(2, notifications)

            val asyncError = runCatching {
                viewModel.updateAsync { error("async failure") }
            }.exceptionOrNull()
            assertTrue(asyncError is IllegalStateException)
            assertEquals(2, notifications)
        } finally {
            binding.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun reset_forceDisposesRetainedInstancesAndClearsRuntimeState() {
        val binding = ViewModelBinding()
        val spec = viewModelSpec(
            key = "alignment-retained",
            aliveForever = true,
        ) { AlignmentViewModel() }
        val viewModel = binding.read(spec)
        binding.dispose()
        assertFalse(viewModel.isDisposed)
        assertTrue(InstanceManager.debugStoreCount > 0)

        ViewModel.initialize(config = ViewModelConfig(isLoggingEnabled = true))
        ViewModel.reset()

        assertTrue(viewModel.isDisposed)
        assertEquals(0, InstanceManager.debugStoreCount)
        assertFalse(ViewModel.config.isLoggingEnabled)

        ViewModel.initialize(config = ViewModelConfig(isLoggingEnabled = true))
        assertTrue(ViewModel.config.isLoggingEnabled)
    }

    @Test
    fun resetInsideBuilder_disposesDetachedInstanceAndDoesNotCacheIt() {
        val binding = ViewModelBinding()
        var created: AlignmentViewModel? = null
        try {
            val spec = viewModelSpec(key = "reset-inside-builder") {
                ViewModel.reset()
                AlignmentViewModel().also { created = it }
            }

            assertThrows(ViewModelError::class.java) { binding.read(spec) }
            assertTrue(created?.isDisposed == true)
            assertEquals(0, InstanceManager.debugStoreCount)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun reset_blocksReentrantResolutionDuringDisposal() {
        val binding = ViewModelBinding()
        val dependencySpec = viewModelSpec(key = "reset-reentrant-dependency") {
            AlignmentViewModel()
        }
        val retainedSpec = viewModelSpec(
            key = "reset-reentrant-owner",
            aliveForever = true,
        ) {
            ReentrantResetViewModel {
                binding.read(dependencySpec)
            }
        }
        val retained = binding.read(retainedSpec)

        ViewModel.reset()

        assertTrue(retained.resolutionError is ViewModelError)
        assertEquals(0, InstanceManager.debugStoreCount)
        binding.dispose()
    }

    @Test
    fun nestedResetDuringDispose_keepsOuterErrorAndLifecyclePipelineUntilTeardownCompletes() {
        var errorReports = 0
        val lifecycleDisposals = mutableListOf<Any?>()
        val lifecycle = object : ViewModelLifecycle {
            override fun onDispose(viewModel: ViewModel, arg: InstanceArg) {
                lifecycleDisposals += arg.key
            }
        }
        ViewModel.initialize(
            config = ViewModelConfig(onError = { _, _ -> errorReports += 1 }),
            lifecycles = listOf(lifecycle),
        )
        val binding = ViewModelBinding()
        val nestedResetSpec = viewModelSpec(
            key = "nested-reset-first",
            aliveForever = true,
        ) {
            ResetPipelineViewModel { ViewModel.reset() }
        }
        val throwingPeerSpec = viewModelSpec(
            key = "nested-reset-peer",
            aliveForever = true,
        ) {
            ResetPipelineViewModel { error("peer dispose failure") }
        }
        val first = binding.read(nestedResetSpec)
        val peer = binding.read(throwingPeerSpec)
        binding.dispose()

        ViewModel.reset()

        assertTrue(first.isDisposed)
        assertTrue(peer.isDisposed)
        assertEquals(listOf("nested-reset-first", "nested-reset-peer"), lifecycleDisposals)
        assertEquals(1, errorReports)
        assertNull(ViewModel.config.onError)
        assertEquals(0, InstanceManager.debugStoreCount)
    }

    @Test
    fun disposedBinding_resolutionThrowsViewModelError() {
        val binding = ViewModelBinding()
        val spec = viewModelSpec(key = "disposed-binding") { AlignmentViewModel() }
        binding.dispose()

        assertThrows(ViewModelError::class.java) { binding.read(spec) }
        assertThrows(ViewModelError::class.java) {
            binding.readCached<AlignmentViewModel>(key = "disposed-binding")
        }
    }

    @Test
    fun maybeCached_returnsNullOnlyForViewModelMisses() {
        val binding = ViewModelBinding()
        try {
            binding.read(viewModelSpec(key = "existing") { AlignmentViewModel() })

            assertNull(ViewModel.maybeReadCached<AlignmentViewModel>(key = "missing"))
            assertNull(binding.maybeReadCached<AlignmentViewModel>(key = "missing"))
            assertNull(binding.maybeWatchCached<AlignmentViewModel>(key = "missing"))

            assertThrows(IllegalStateException::class.java) {
                ViewModel.maybeReadCached<AlignmentViewModel>(key = ExplodingHashKey)
            }
            assertThrows(IllegalStateException::class.java) {
                binding.maybeReadCached<AlignmentViewModel>(key = ExplodingHashKey)
            }
            assertThrows(IllegalStateException::class.java) {
                binding.maybeWatchCached<AlignmentViewModel>(key = ExplodingHashKey)
            }
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun removedGeneralListener_isSkippedWithinTheCurrentNotification() {
        val binding = ViewModelBinding()
        try {
            val viewModel = binding.read(viewModelSpec { AlignmentViewModel() })
            val calls = mutableListOf<String>()
            var removeSecond: () -> Unit = {}
            viewModel.listen {
                calls += "first"
                removeSecond()
            }
            removeSecond = viewModel.listen { calls += "second" }

            viewModel.emit()

            assertEquals(listOf("first"), calls)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun removedBindingUpdateListener_isSkippedWithinTheCurrentNotification() {
        val binding = ViewModelBinding()
        try {
            val calls = mutableListOf<String>()
            var removeSecond: () -> Unit = {}
            binding.addUpdateListener {
                calls += "first"
                removeSecond()
            }
            removeSecond = binding.addUpdateListener { calls += "second" }

            binding.onUpdate()

            assertEquals(listOf("first"), calls)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun removedOwnerDiagnosticListener_isSkippedWithinTheCurrentNotification() {
        val firstBinding = ViewModelBinding()
        val secondBinding = ViewModelBinding()
        try {
            val spec = viewModelSpec(key = "owner-listener-mutation") { AlignmentViewModel() }
            val viewModel = firstBinding.read(spec)
            val calls = mutableListOf<String>()
            var removeSecond: () -> Unit = {}
            viewModel.refHandler.addOwnerChangeListener { _, _, _ ->
                calls += "first"
                removeSecond()
            }
            removeSecond = viewModel.refHandler.addOwnerChangeListener { _, _, _ ->
                calls += "second"
            }

            secondBinding.read(spec)

            assertEquals(listOf("first"), calls)
        } finally {
            secondBinding.dispose()
            firstBinding.dispose()
        }
    }

    @Test
    fun removedStateListener_isSkippedWithinTheCurrentTransition() {
        val binding = ViewModelBinding()
        try {
            val viewModel = binding.read(alignmentStateSpec())
            val calls = mutableListOf<String>()
            var removeSecond: () -> Unit = {}
            viewModel.listenState { _, _ ->
                calls += "first"
                removeSecond()
            }
            removeSecond = viewModel.listenState { _, _ -> calls += "second" }

            viewModel.change(value = 1)

            assertEquals(listOf("first"), calls)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun reentrantStateChanges_keepEachTransitionFrozenForLaterListeners() {
        val binding = ViewModelBinding()
        try {
            val viewModel = binding.read(alignmentStateSpec())
            val observed = mutableListOf<Pair<Int, Int>>()
            viewModel.listenState { _, current ->
                if (current.value == 1) viewModel.change(value = 2)
            }
            viewModel.listenState { previous, current ->
                observed += previous!!.value to current.value
            }

            viewModel.change(value = 1)

            assertEquals(listOf(1 to 2, 0 to 1), observed)
            assertEquals(1, viewModel.previousState?.value)
            assertEquals(2, viewModel.state.value)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun notifyListeners_doesNotReplayStateDiffs() {
        val binding = ViewModelBinding()
        try {
            val viewModel = binding.read(alignmentStateSpec())
            var stateCalls = 0
            var generalCalls = 0
            viewModel.listenState { _, _ -> stateCalls += 1 }
            viewModel.listen { generalCalls += 1 }

            viewModel.change(value = 1)
            viewModel.emitBroad()

            assertEquals(1, stateCalls)
            assertEquals(2, generalCalls)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun fullStateEquality_usesLocalThenGlobalThenReferenceIdentity() {
        ViewModel.initialize(
            config = ViewModelConfig(equals = { _, _ -> true }),
        )
        val localBinding = ViewModelBinding()
        try {
            val local = localBinding.read(
                alignmentStateSpec(equals = { previous, current -> previous.value == current.value }),
            )
            local.change(value = 0, label = "local-suppressed")
            assertEquals("initial", local.state.label)
            local.change(value = 1)
            assertEquals(1, local.state.value)
        } finally {
            localBinding.dispose()
        }

        ViewModel.reset()
        ViewModel.initialize(
            config = ViewModelConfig(
                equals = { previous, current ->
                    previous is AlignmentState &&
                        current is AlignmentState &&
                        previous.value == current.value
                },
            ),
        )
        val globalBinding = ViewModelBinding()
        try {
            val global = globalBinding.read(alignmentStateSpec())
            global.change(value = 0, label = "global-suppressed")
            assertEquals("initial", global.state.label)
        } finally {
            globalBinding.dispose()
        }

        ViewModel.reset()
        val identityBinding = ViewModelBinding()
        try {
            val spec = viewModelSpec { IdentityStateViewModel() }
            val identity = identityBinding.read(spec)
            val original = identity.state
            identity.change(original)
            assertSame(original, identity.state)
            identity.change(IdentityState(original.value))
            assertFalse(identity.state === original)
        } finally {
            identityBinding.dispose()
        }
    }

    @Test
    fun selectorEquality_usesGlobalFallbackAndLocalOverride() {
        ViewModel.initialize(
            config = ViewModelConfig(
                equals = { previous, current ->
                    when {
                        previous is AlignmentState && current is AlignmentState -> false
                        previous is Int && current is Int -> previous % 2 == current % 2
                        else -> previous === current
                    }
                },
            ),
        )
        val binding = ViewModelBinding()
        try {
            val viewModel = binding.read(alignmentStateSpec())
            val globalChanges = mutableListOf<Pair<Int?, Int>>()
            viewModel.listenStateSelect(
                selector = AlignmentState::value,
                onChanged = { previous, current -> globalChanges += previous to current },
            )
            viewModel.change(value = 2)
            viewModel.change(value = 3)
            assertEquals(listOf(2 to 3), globalChanges)

            val localChanges = mutableListOf<Pair<Int?, Int>>()
            viewModel.listenStateSelect(
                selector = AlignmentState::value,
                equals = { _, _ -> false },
                onChanged = { previous, current -> localChanges += previous to current },
            )
            viewModel.change(value = 5)
            assertEquals(listOf(3 to 5), localChanges)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun selectorEquality_fallsBackToKotlinEquality() {
        val binding = ViewModelBinding()
        try {
            val viewModel = binding.read(alignmentStateSpec())
            val changes = mutableListOf<Pair<Int?, Int>>()
            viewModel.listenStateSelect(
                selector = AlignmentState::value,
                onChanged = { previous, current -> changes += previous to current },
            )

            viewModel.change(value = 0, label = "same-selected-value")
            viewModel.change(value = 1)

            assertEquals(listOf(0 to 1), changes)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun throwingErrorHandler_doesNotStopListenersOrDisposeCallbacks() {
        var reports = 0
        ViewModel.initialize(
            config = ViewModelConfig(
                onError = { _, _ ->
                    reports += 1
                    error("handler failure")
                },
            ),
        )
        val binding = ViewModelBinding()
        val viewModel = binding.read(viewModelSpec { AlignmentViewModel() })
        var laterListenerCalls = 0
        var laterDisposeCalls = 0
        viewModel.listen { error("listener failure") }
        viewModel.listen { laterListenerCalls += 1 }
        viewModel.registerDispose { error("dispose failure") }
        viewModel.registerDispose { laterDisposeCalls += 1 }

        viewModel.emit()
        binding.dispose()

        assertEquals(1, laterListenerCalls)
        assertEquals(1, laterDisposeCalls)
        assertTrue(reports >= 2)
        assertTrue(viewModel.isDisposed)
    }

    @Test
    fun throwingStateComparator_isReportedAndDoesNotMutateState() {
        var reportedType: ErrorType? = null
        ViewModel.initialize(
            config = ViewModelConfig(onError = { _, type -> reportedType = type }),
        )
        val binding = ViewModelBinding()
        try {
            val viewModel = binding.read(
                alignmentStateSpec(equals = { _, _ -> error("equals failure") }),
            )

            viewModel.change(value = 1)

            assertEquals(0, viewModel.state.value)
            assertEquals(ErrorType.Listener, reportedType)
        } finally {
            binding.dispose()
        }
    }
}

private class AlignmentViewModel : ViewModel() {
    fun emit() = notifyListeners()

    fun registerDispose(block: () -> Unit) = addDispose(block)
}

private class ReentrantResetViewModel(
    private val resolveDuringDispose: () -> Unit,
) : ViewModel() {
    var resolutionError: Throwable? = null
        private set

    override fun dispose() {
        resolutionError = runCatching(resolveDuringDispose).exceptionOrNull()
    }
}

private class ResetPipelineViewModel(
    private val onDisposeBlock: () -> Unit,
) : ViewModel() {
    override fun dispose() {
        onDisposeBlock()
    }
}

private data class AlignmentState(
    val value: Int,
    val label: String,
)

private class AlignmentStateViewModel(
    equals: ((AlignmentState, AlignmentState) -> Boolean)?,
) : StateViewModel<AlignmentState>(
    initialState = AlignmentState(value = 0, label = "initial"),
    equals = equals,
) {
    fun change(
        value: Int,
        label: String = state.label,
    ) {
        setState(AlignmentState(value = value, label = label))
    }

    fun emitBroad() = notifyListeners()
}

private fun alignmentStateSpec(
    equals: ((AlignmentState, AlignmentState) -> Boolean)? = null,
): ViewModelSpec<AlignmentStateViewModel> = viewModelSpec {
    AlignmentStateViewModel(equals)
}

private class IdentityState(val value: Int)

private class IdentityStateViewModel : StateViewModel<IdentityState>(IdentityState(1)) {
    fun change(value: IdentityState) = setState(value)
}

private object ExplodingHashKey {
    override fun hashCode(): Int = error("unexpected key failure")
}
