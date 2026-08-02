package milu.viewmodel

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
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
                    composed = readViewModel(spec, binding)
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
            }
        } finally {
            binding.dispose()
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
}

private object EmptyApplier : Applier<Unit> {
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
