package milu.viewmodel

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BindingLifecycleRegressionTest {
    @Before
    fun setUp() {
        ViewModel.reset()
    }

    @After
    fun tearDown() {
        ViewModel.reset()
    }

    @Test
    fun aggregatePause_onlyChangesOnCombinedTransitions() {
        val transitions = mutableListOf<String>()
        val controller = PauseAwareController(
            onPause = { transitions += "pause" },
            onResume = { transitions += "resume" },
        )
        val first = RegressionPauseProvider()
        val second = RegressionPauseProvider()
        try {
            controller.addProvider(first)
            controller.addProvider(second)
            first.change(true)
            second.change(true)
            first.change(false)

            assertTrue(controller.isPaused)
            assertEquals(listOf("pause"), transitions)

            second.change(false)
            assertFalse(controller.isPaused)
            assertEquals(listOf("pause", "resume"), transitions)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun aggregatePause_addRemoveProvidersOnlyNotifiesOnEdges() {
        val transitions = mutableListOf<String>()
        val controller = PauseAwareController(
            onPause = { transitions += "pause" },
            onResume = { transitions += "resume" },
        )
        val first = RegressionPauseProvider(initiallyPaused = true)
        val second = RegressionPauseProvider(initiallyPaused = true)
        val neutral = RegressionPauseProvider()
        try {
            controller.addProvider(first)
            controller.addProvider(first)
            controller.addProvider(second)
            controller.removeProvider(first)
            assertTrue(controller.isPaused)
            assertEquals(listOf("pause"), transitions)

            controller.removeProvider(second)
            controller.addProvider(neutral)
            controller.removeProvider(neutral)
            controller.removeProvider(neutral)
            assertFalse(controller.isPaused)
            assertEquals(listOf("pause", "resume"), transitions)
            assertEquals(1, first.disposeCalls)
            assertEquals(1, second.disposeCalls)
            assertEquals(1, neutral.disposeCalls)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun aggregatePause_initialCallbackFailureIsReported() {
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(config = ViewModelConfig(onError = { _, type -> errors += type }))
        val controller = PauseAwareController(
            onPause = { error("pause callback failed") },
            onResume = {},
        )
        try {
            controller.addProvider(RegressionPauseProvider(initiallyPaused = true))
            assertTrue(controller.isPaused)
            assertEquals(listOf(ErrorType.PauseResume), errors)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun aggregatePause_throwingDisposerStillResumes() {
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(config = ViewModelConfig(onError = { _, type -> errors += type }))
        var resumes = 0
        val controller = PauseAwareController(onPause = {}, onResume = { resumes += 1 })
        val provider = RegressionPauseProvider(initiallyPaused = true, failOnDispose = true)
        try {
            controller.addProvider(provider)
            controller.removeProvider(provider)
            assertFalse(controller.isPaused)
            assertEquals(1, resumes)
            assertEquals(listOf(ErrorType.PauseResume), errors)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun aggregatePause_disposeIsTerminalAndDoesNotResume() {
        var resumes = 0
        val controller = PauseAwareController(onPause = {}, onResume = { resumes += 1 })
        val provider = RegressionPauseProvider(initiallyPaused = true)
        val rejected = RegressionPauseProvider()
        try {
            controller.addProvider(provider)
            controller.dispose()
            controller.dispose()
            assertEquals(1, provider.disposeCalls)
            assertEquals(0, resumes)
            assertThrows(ViewModelError::class.java) { controller.addProvider(rejected) }
        } finally {
            rejected.dispose()
            controller.dispose()
        }
    }

    @Test
    fun pausedBinding_waitsForEveryProviderBeforeFlushing() {
        var updates = 0
        val binding = ViewModelBinding { updates += 1 }
        val first = RegressionPauseProvider()
        val second = RegressionPauseProvider()
        val spec = viewModelSpec { LifecycleRegressionViewModel() }
        try {
            binding.addPauseProvider(first)
            binding.addPauseProvider(second)
            val viewModel = binding.watch(spec)
            first.change(true)
            second.change(true)
            viewModel.notifyListeners()
            viewModel.notifyListeners()
            assertEquals(0, updates)

            first.change(false)
            assertTrue(binding.isPaused)
            assertEquals(0, updates)

            second.change(false)
            assertFalse(binding.isPaused)
            assertEquals(1, updates)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun retainedBinding_resumesAfterOwnerRecreation() {
        val store = ViewModelStore()
        val originalOwner = RegressionLifecycleOwner(store)
        val replacementOwner = RegressionLifecycleOwner(store)
        val spec = viewModelSpec { LifecycleRegressionViewModel() }
        try {
            val binding = originalOwner.viewModelBinding
            binding.watch(spec)
            originalOwner.lifecycle.currentState = Lifecycle.State.STARTED
            originalOwner.lifecycle.currentState = Lifecycle.State.CREATED
            assertTrue(binding.isPaused)
            originalOwner.lifecycle.currentState = Lifecycle.State.DESTROYED

            val retainedBinding = replacementOwner.viewModelBinding
            assertSame(binding, retainedBinding)
            assertFalse(binding.isDisposed)
            assertTrue(binding.isPaused)
            var updates = 0
            binding.addUpdateListener { updates += 1 }
            retainedBinding.watch(spec).notifyListeners()
            assertEquals(0, updates)

            replacementOwner.lifecycle.currentState = Lifecycle.State.STARTED
            assertFalse(binding.isPaused)
            assertEquals(1, updates)
            retainedBinding.watch(spec).notifyListeners()
            assertEquals(2, updates)
        } finally {
            originalOwner.lifecycle.currentState = Lifecycle.State.DESTROYED
            replacementOwner.lifecycle.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }

    @Test
    fun retainedBinding_ignoresDestroyedOwners() {
        val store = ViewModelStore()
        val activeOwner = RegressionLifecycleOwner(store)
        val destroyedOwner = RegressionLifecycleOwner(store)
        try {
            activeOwner.lifecycle.currentState = Lifecycle.State.STARTED
            destroyedOwner.lifecycle.currentState = Lifecycle.State.DESTROYED
            val binding = activeOwner.viewModelBinding
            assertSame(binding, destroyedOwner.viewModelBinding)
            assertFalse(binding.isPaused)
        } finally {
            activeOwner.lifecycle.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }

    @Test
    fun retainedBinding_clearsLifecycleObserversWithStore() {
        val store = ViewModelStore()
        val owner = RegressionLifecycleOwner(store)
        try {
            val binding = owner.viewModelBinding
            assertSame(binding, owner.viewModelBinding)
            assertTrue(owner.lifecycle.observerCount > 0)

            store.clear()

            assertTrue(binding.isDisposed)
            assertEquals(0, owner.lifecycle.observerCount)
        } finally {
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            store.clear()
        }
    }

    @Test
    fun disposedBinding_tagLookupsCannotAcquireOwnership() {
        val owner = ViewModelBinding()
        val disposed = ViewModelBinding()
        val spec = viewModelSpec(key = "tagged-regression", tag = "regression") {
            LifecycleRegressionViewModel()
        }
        try {
            val viewModel = owner.read(spec)
            disposed.dispose()
            assertThrows(ViewModelError::class.java) {
                disposed.readCachesByTag<LifecycleRegressionViewModel>("regression")
            }
            assertThrows(ViewModelError::class.java) {
                disposed.watchCachesByTag<LifecycleRegressionViewModel>("regression")
            }

            owner.dispose()
            assertTrue(viewModel.isDisposed)
        } finally {
            disposed.dispose()
            owner.dispose()
        }
    }

    @Test
    fun disposingBinding_tagLookupsAreRejectedDuringTeardown() {
        val owner = ViewModelBinding()
        val disposing = ViewModelBinding()
        val spec = viewModelSpec(key = "teardown-regression", tag = "regression") {
            LifecycleRegressionViewModel()
        }
        try {
            val viewModel = owner.read(spec)
            var readError: Throwable? = null
            var watchError: Throwable? = null
            disposing.registerListenerDisposer {
                readError = runCatching {
                    disposing.readCachesByTag<LifecycleRegressionViewModel>("regression")
                }.exceptionOrNull()
                watchError = runCatching {
                    disposing.watchCachesByTag<LifecycleRegressionViewModel>("regression")
                }.exceptionOrNull()
            }
            disposing.dispose()

            assertTrue(readError is ViewModelError)
            assertTrue(watchError is ViewModelError)
            owner.dispose()
            assertTrue(viewModel.isDisposed)
        } finally {
            disposing.dispose()
            owner.dispose()
        }
    }

    @Test
    fun disposedBinding_missingTagQueriesStillReject() {
        val binding = ViewModelBinding()
        binding.dispose()
        assertThrows(ViewModelError::class.java) {
            binding.readCachesByTag<LifecycleRegressionViewModel>("missing")
        }
        assertThrows(ViewModelError::class.java) {
            binding.watchCachesByTag<LifecycleRegressionViewModel>("missing")
        }
    }
}

private class LifecycleRegressionViewModel : ViewModel()

private class RegressionPauseProvider(
    initiallyPaused: Boolean = false,
    private val failOnDispose: Boolean = false,
) : BasePauseProvider() {
    var disposeCalls = 0
        private set

    init {
        setPaused(initiallyPaused)
    }

    fun change(paused: Boolean) = setPaused(paused)

    override fun dispose() {
        super.dispose()
        disposeCalls += 1
        if (failOnDispose) error("pause provider dispose failed")
    }
}

private class RegressionLifecycleOwner(
    override val viewModelStore: ViewModelStore,
) : LifecycleOwner, ViewModelStoreOwner {
    // JVM tests are deliberately single-threaded; no Android main looper is needed.
    override val lifecycle: LifecycleRegistry = LifecycleRegistry.createUnsafe(this).apply {
        currentState = Lifecycle.State.CREATED
    }
}
