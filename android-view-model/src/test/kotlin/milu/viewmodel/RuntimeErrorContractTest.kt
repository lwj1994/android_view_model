package milu.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeErrorContractTest {
    private val bindings = mutableListOf<ViewModelBinding>()

    private fun binding() = ViewModelBinding().also(bindings::add)

    @Before fun setUp() = ViewModel.reset()

    @After fun tearDown() {
        bindings.asReversed().forEach { it.dispose() }
        ViewModel.reset()
    }

    @Test fun initializationIsIdempotentAndResetRestoresDefaults() {
        val config = ViewModelConfig(isLoggingEnabled = true)
        ViewModel.initialize(config)
        ViewModel.initialize(ViewModelConfig(isDebugMode = true))
        assertSame(config, ViewModel.config)
        var logged = false
        viewModelLog { logged = true; "enabled" }
        assertTrue(logged)
        @Suppress("DEPRECATION")
        ViewModel.debugReset()
        assertEquals(ViewModelConfig(), ViewModel.config)
        viewModelLog { error("disabled logging must be lazy") }
        ViewModel.initialize()
        assertFalse(ViewModel.config.isLoggingEnabled)
    }

    @Test fun failingLifecycleObserversDoNotPreventLaterObserversOrCleanup() {
        val errors = mutableListOf<ErrorType>()
        val events = mutableListOf<String>()
        ViewModel.initialize(ViewModelConfig(onError = { _, type -> errors += type }))
        val remove = ViewModel.addLifecycle(object : ViewModelLifecycle {
            override fun onCreate(viewModel: ViewModel, arg: InstanceArg) = error("create")
            override fun onBind(viewModel: ViewModel, arg: InstanceArg, bindingId: String) = error("bind")
            override fun onUnbind(viewModel: ViewModel, arg: InstanceArg, bindingId: String) = error("unbind")
            override fun onDispose(viewModel: ViewModel, arg: InstanceArg) = error("dispose")
        })
        ViewModel.addLifecycle(object : ViewModelLifecycle {})
        ViewModel.addLifecycle(object : ViewModelLifecycle {
            override fun onCreate(viewModel: ViewModel, arg: InstanceArg) { events += "create" }
            override fun onBind(viewModel: ViewModel, arg: InstanceArg, bindingId: String) { events += "bind" }
            override fun onUnbind(viewModel: ViewModel, arg: InstanceArg, bindingId: String) { events += "unbind" }
            override fun onDispose(viewModel: ViewModel, arg: InstanceArg) { events += "dispose" }
        })
        val owner = binding()
        val spec = viewModelSpec(tag = "lifecycle") { ViewModel() }
        val vm by owner.readViewModel(spec)
        assertEquals("lifecycle", vm.tag)
        owner.dispose()
        assertEquals(listOf("create", "bind", "unbind", "dispose"), events)
        assertEquals(listOf(ErrorType.Lifecycle, ErrorType.Lifecycle, ErrorType.Lifecycle, ErrorType.Dispose), errors)
        remove()
        remove()
    }

    @Test fun removalDuringNotificationSkipsCancelledCallbacksAndErrorsAreIsolated() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val errors = mutableListOf<ErrorType>()
            ViewModel.initialize(ViewModelConfig(onError = { _, type -> errors += type }))
            val owner = binding()
            val spec = viewModelSpec { ViewModel() }
            val vm by owner.readViewModel(spec)
            var removeSecond: () -> Unit = {}
            vm.listen { removeSecond(); error("first") }
            removeSecond = vm.listen { error("cancelled callback") }
            var changes = 0
            val removeThird = vm.listen { changes++ }
            assertTrue(vm.hasListeners)
            vm.update { changes += 10 }
            vm.updateAsync { changes += 100 }
            assertEquals(112, changes)
            assertEquals(listOf(ErrorType.Listener, ErrorType.Listener), errors)
            removeThird()
            val old = vm
            owner.dispose()
            old.onDispose(old.instanceArg)
            old.notifyListeners()
            assertFalse(old.hasListeners)
            assertThrows(ViewModelError::class.java) { old.viewModelBinding }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun generationUpdateAndDisposerFailuresAreReportedWithoutInterruptingOthers() {
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(ViewModelConfig(onError = { _, type -> errors += type }))
        val owner = binding()
        val spec = viewModelSpec { ViewModel() }
        val vm by owner.readViewModel(spec)
        var removedGeneration: () -> Unit = {}
        owner.addGenerationChangeListener { removedGeneration(); error("generation") }
        removedGeneration = owner.addGenerationChangeListener { error("removed generation") }
        var generations = 0
        owner.addGenerationChangeListener { generations++ }
        var removedUpdate: () -> Unit = {}
        owner.addUpdateListener { removedUpdate(); error("update") }
        removedUpdate = owner.addUpdateListener { error("removed update") }
        var updates = 0
        owner.addUpdateListener { updates++ }
        owner.registerListenerDisposer { error("dispose callback") }
        var disposed = false
        owner.registerListenerDisposer { disposed = true }
        owner.recycle(vm)
        assertEquals(1, generations)
        assertEquals(1, updates)
        owner.dispose()
        assertTrue(disposed)
        assertEquals(listOf(ErrorType.Listener, ErrorType.Listener, ErrorType.Dispose), errors)
    }

    @Test fun lateCleanupRunsImmediatelyAndThrowingErrorHandlerCannotBreakReporting() {
        val cleanup = AutoDisposeController()
        var calls = 0
        cleanup.addDispose { calls++ }
        cleanup.dispose()
        cleanup.addDispose { calls++ }
        cleanup.dispose()
        assertEquals(2, calls)
        reportViewModelError(IllegalStateException("no handler"), ErrorType.Listener, "test")
        ViewModel.initialize(ViewModelConfig(onError = { _, _ -> error("handler") }))
        reportViewModelError(IllegalStateException("original"), ErrorType.Listener, "test")
    }

    @Test fun stateEqualityFailuresPreserveStateAndCancellationPropagates() {
        val owner = binding()
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(ViewModelConfig(isLoggingEnabled = true, onError = { _, type -> errors += type }))
        val badSpec = viewModelSpec { StateViewModel(1, equals = { _, _ -> error("equals") }) }
        val bad by owner.readViewModel(badSpec)
        bad.setState(2)
        assertEquals(1, bad.initialState)
        assertEquals(1, bad.state)
        assertNull(bad.previousState)
        assertEquals(listOf(ErrorType.Listener), errors)
        val cancelledSpec = viewModelSpec(key = "cancel") {
            StateViewModel(1, equals = { _, _ -> throw CancellationException("stop") })
        }
        val cancelled by owner.readViewModel(cancelledSpec)
        assertThrows(CancellationException::class.java) { cancelled.setState(2) }
        val old = bad
        owner.dispose()
        old.setState(3)
        old.notifyListeners()
        assertEquals(1, old.state)
    }

    @Test fun simpleBindingScopeDisposesAndCallbackScopeObservesChanges() {
        val spec = viewModelSpec { ViewModel() }
        val plain = ViewModelBindingScope()
        val vm by plain.viewModelBinding.readViewModel(spec)
        assertFalse(vm.isDisposed)
        plain.close()
        plain.dispose()
        assertTrue(plain.viewModelBinding.isDisposed)
        var updates = 0
        val observed = ViewModelBindingScope { updates++ }
        try {
            val watched by observed.viewModelBinding.watchViewModel(spec)
            watched.notifyListeners()
            assertEquals(1, updates)
        } finally {
            observed.close()
        }
    }
}
