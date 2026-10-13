package milu.viewmodel

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposeBindingsTest {
    @Before
    fun setUp() {
        ViewModel.reset()
    }

    @After
    fun tearDown() {
        ViewModel.reset()
    }

    @Test
    fun readViewModel_reResolvesAfterRecycle() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val binding = ViewModelBinding()
        var generation = 0
        val spec = viewModelSpec(key = "compose-generation") {
            ComposeGenerationViewModel(++generation)
        }
        try {
            withTestComposition {
                var composed: ComposeGenerationViewModel? = null
                setContent {
                    val current by readViewModel(spec, binding)
                    composed = current
                }
                awaitIdle()
                val first = requireNotNull(composed)

                binding.recycle(first)
                awaitIdle()
                val second = requireNotNull(composed)

                assertTrue(first.isDisposed)
                assertNotSame(first, second)
                assertEquals(1, first.generation)
                assertEquals(2, second.generation)
            }
        } finally {
            binding.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun selectViewModelState_recomposesOnlyForSelectedChanges() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val binding = ViewModelBinding()
        val spec = viewModelSpec(key = "compose-selector") {
            ComposeStateViewModel()
        }
        val broadSpec = viewModelSpec(key = "compose-broad-neighbor") {
            ComposeBroadViewModel()
        }
        val viewModel = binding.read(spec)
        val broadViewModel = binding.watch(broadSpec)
        try {
            withTestComposition {
                var selected = -1
                var recompositions = 0
                setContent {
                    recompositions += 1
                    selected = selectViewModelState(
                        factory = spec,
                        selector = { it.count },
                        binding = binding,
                    )
                }
                awaitIdle()
                assertEquals(0, selected)
                assertEquals(1, recompositions)

                broadViewModel.emit()
                awaitIdle()
                assertEquals(1, recompositions)

                viewModel.change(label = "same selection")
                awaitIdle()
                assertEquals(0, selected)
                assertEquals(1, recompositions)

                viewModel.change(count = 1)
                awaitIdle()
                assertEquals(1, selected)
                assertEquals(2, recompositions)
                assertSame(viewModel, binding.read(spec))

                binding.recycle(viewModel)
                awaitIdle()
                assertEquals(0, selected)
                val replacement = binding.read(spec)
                assertNotSame(viewModel, replacement)
                replacement.change(count = 2)
                awaitIdle()
                assertEquals(2, selected)
            }
        } finally {
            binding.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun selectViewModelState_withStableSelectorRendersLatestReentrantState() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val binding = ViewModelBinding()
        val spec = viewModelSpec { ComposeStateViewModel() }
        val viewModel by binding.readViewModel(spec)
        // Reuse the same function across recompositions so remember cannot hide a stale callback.
        val selector: (ComposeState) -> Int = { it.count }
        try {
            binding.listenState(spec) { _, current ->
                if (current.count == 1) viewModel.change(count = 2)
            }
            withTestComposition {
                var rendered = -1
                var recompositions = 0
                setContent {
                    recompositions += 1
                    rendered = selectViewModelState(
                        factory = spec,
                        selector = selector,
                        binding = binding,
                    )
                }
                awaitIdle()
                assertEquals(0, rendered)

                viewModel.change(count = 1)
                awaitIdle()

                assertEquals(2, viewModel.state.count)
                assertEquals(2, rendered)
                assertEquals(2, recompositions)
            }
        } finally {
            binding.dispose()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun delegates_callbacksResolveBeforeRecomposition_andKeepNotificationSemantics() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            for (watch in listOf(false, true)) {
                val binding = ViewModelBinding()
                var generation = 0
                val spec = viewModelSpec { ComposeGenerationViewModel(++generation) }
                try {
                    withTestComposition {
                        var callback: () -> ComposeGenerationViewModel = { error("Not composed") }
                        var recompositions = 0
                        var renderedGeneration = 0
                        setContent {
                            recompositions++
                            val model by if (watch) {
                                watchViewModel(spec, binding)
                            } else {
                                readViewModel(spec, binding)
                            }
                            renderedGeneration = model.generation
                            callback = { model }
                        }
                        awaitIdle()
                        val originalCallback = callback
                        val first = originalCallback()
                        repeat(10) { assertSame(first, originalCallback()) }
                        first.notifyListeners()
                        awaitIdle()
                        assertEquals(if (watch) 2 else 1, recompositions)

                        binding.recycle(first)
                        // The retained callback resolves the new generation without waiting for recomposition.
                        val second = originalCallback()
                        assertNotSame(first, second)
                        assertEquals(2, second.generation)
                        awaitIdle()
                        assertEquals(2, renderedGeneration)
                        assertSame(second, callback())
                        assertEquals(2, generation)
                    }
                } finally {
                    binding.dispose()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun defaultProviderSharesOwnershipAndDisposesWhenRemoved() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val spec = viewModelSpec { ComposeStateViewModel() }
        var owned: ViewModelBinding? = null
        var rendered: ComposeState? = null
        var selected = -1
        val selector: (ComposeState) -> Int = { it.count }
        try {
            assertNotNull(LocalViewModelBinding)
            withTestComposition {
                setContent {
                    ViewModelBindingProvider {
                        owned = currentViewModelBinding()
                        val read by readViewModel(spec, keys = arrayOf("read"))
                        val watched by watchViewModel(spec, keys = arrayOf("watch"))
                        assertSame(read, watched)
                        rendered = watchViewModelState(spec, keys = arrayOf("state"))
                        selected = selectViewModelState(spec, selector, keys = arrayOf("select"))
                    }
                }
                awaitIdle()
                assertEquals(0, rendered!!.count)
                assertEquals(0, selected)
                val binding = requireNotNull(owned)
                invalidateAll()
                awaitIdle()
                assertSame(binding, owned)
                val vm by binding.readViewModel(spec)
                vm.change(count = 2)
                awaitIdle()
                assertEquals(2, rendered!!.count)
                assertEquals(2, selected)
                setContent {}
                awaitIdle()
                assertTrue(binding.isDisposed)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun providerFreeCallSitesHaveIndependentCompositionLifetimes() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        var first: ViewModelBinding? = null
        var second: ViewModelBinding? = null
        try {
            withTestComposition {
                setContent {
                    first = currentViewModelBinding()
                    second = currentViewModelBinding()
                }
                awaitIdle()
                assertNotSame(first, second)
            }
            assertTrue(first!!.isDisposed)
            assertTrue(second!!.isDisposed)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun screenBindingUsesCurrentStoreAndIgnoresEnclosingProvider() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val enclosing = ViewModelBinding()
        var screen: ViewModelBinding? = null
        try {
            withTestComposition {
                setContent {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        ViewModelBindingProvider(enclosing) {
                            screen = rememberScreenViewModelBinding()
                            assertSame(screen, rememberScreenViewModelBinding())
                            assertSame(enclosing, currentViewModelBinding())
                        }
                    }
                }
                awaitIdle()
                assertSame(owner.viewModelBinding, screen)
                assertNotSame(enclosing, screen)
            }
            assertTrue(!screen!!.isDisposed)
            assertTrue(!enclosing.isDisposed)
            owner.viewModelStore.clear()
            assertTrue(screen!!.isDisposed)
        } finally {
            enclosing.dispose()
            owner.viewModelStore.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun changingProviderAndSelectorEqualityReattachesToCorrectOwner() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val first = ViewModelBinding()
        val second = ViewModelBinding()
        val active = mutableStateOf(first)
        val spec = viewModelSpec { ComposeStateViewModel() }
        val firstVm by first.readViewModel(spec)
        val secondVm by second.readViewModel(spec)
        val selector: (ComposeState) -> Int = { it.count }
        val parity: (Int, Int) -> Boolean = { a, b -> a % 2 == b % 2 }
        var rendered = -1
        try {
            withTestComposition {
                setContent {
                    ViewModelBindingProvider(active.value) {
                        rendered = selectViewModelState(spec, selector, equals = parity)
                    }
                }
                awaitIdle()
                firstVm.change(count = 2)
                awaitIdle()
                assertEquals(0, rendered)
                firstVm.change(count = 3)
                awaitIdle()
                assertEquals(3, rendered)
                active.value = second
                awaitIdle()
                assertEquals(0, rendered)
                firstVm.change(count = 4)
                awaitIdle()
                assertEquals(0, rendered)
                secondVm.change(count = 1)
                awaitIdle()
                assertEquals(1, rendered)
            }
            assertTrue(!first.isDisposed)
            assertTrue(!second.isDisposed)
        } finally {
            first.dispose()
            second.dispose()
            Dispatchers.resetMain()
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.withTestComposition(
        block: suspend TestComposition.() -> Unit,
    ) {
        val recomposer = Recomposer(coroutineContext + ImmediateFrameClock)
        val composition = Composition(EmptyApplier, recomposer)
        val runner = launch(ImmediateFrameClock, start = CoroutineStart.UNDISPATCHED) {
            recomposer.runRecomposeAndApplyChanges()
        }
        try {
            TestComposition(composition, recomposer).block()
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.cancelAndJoin()
        }
    }
}

private class TestComposition(
    private val composition: Composition,
    private val recomposer: Recomposer,
) {
    fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        composition.setContent(content)
    }

    suspend fun awaitIdle() {
        Snapshot.sendApplyNotifications()
        recomposer.awaitIdle()
    }

    @OptIn(InternalComposeApi::class)
    fun invalidateAll() {
        (composition as ControlledComposition).invalidateAll()
    }
}

internal object EmptyApplier : Applier<Unit> {
    override val current: Unit = Unit

    override fun down(node: Unit) = Unit

    override fun up() = Unit

    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun remove(index: Int, count: Int) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun clear() = Unit
}

private object ImmediateFrameClock : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
        onFrame(System.nanoTime())
}

private class ComposeGenerationViewModel(val generation: Int) : ViewModel()

private class ComposeBroadViewModel : ViewModel() {
    fun emit() = notifyListeners()
}

private data class ComposeState(
    val count: Int,
    val label: String,
)

private class ComposeStateViewModel : StateViewModel<ComposeState>(
    initialState = ComposeState(count = 0, label = "initial"),
) {
    fun change(
        count: Int = state.count,
        label: String = state.label,
    ) {
        setState(ComposeState(count = count, label = label))
    }
}
