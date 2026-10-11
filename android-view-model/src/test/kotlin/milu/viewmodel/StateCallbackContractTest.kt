package milu.viewmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StateCallbackContractTest {
    @Before
    fun setUp() {
        ViewModel.reset()
    }

    @After
    fun tearDown() {
        ViewModel.reset()
    }

    @Test
    fun stateCallbacks_preserveNonNullableTypesAndDoNotReplayInitialState() {
        val binding = ViewModelBinding()
        try {
            val spec = viewModelSpec { StateViewModel(0) }
            val viewModel by binding.readViewModel(spec)
            val directChanges = mutableListOf<Pair<Int, Int>>()
            val bindingChanges = mutableListOf<Pair<Int, Int>>()
            val directListener: (Int, Int) -> Unit = { previous, current ->
                directChanges += previous to current
            }
            val bindingListener: (Int, Int) -> Unit = { previous, current ->
                bindingChanges += previous to current
            }

            viewModel.listenState(directListener)
            binding.listenState(spec, bindingListener)

            assertTrue(directChanges.isEmpty())
            assertTrue(bindingChanges.isEmpty())
            assertNull(viewModel.previousState)

            viewModel.setState(1)
            viewModel.setState(2)
            viewModel.notifyListeners()

            assertEquals(listOf(0 to 1, 1 to 2), directChanges)
            assertEquals(directChanges, bindingChanges)
            assertEquals(1, viewModel.previousState)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun stateCallbacks_preserveNullWhenStateItselfIsNullable() {
        val binding = ViewModelBinding()
        try {
            val spec = viewModelSpec { StateViewModel<Int?>(null) }
            val viewModel by binding.readViewModel(spec)
            val directChanges = mutableListOf<Pair<Int?, Int?>>()
            val bindingChanges = mutableListOf<Pair<Int?, Int?>>()
            val directListener: (Int?, Int?) -> Unit = { previous, current ->
                directChanges += previous to current
            }
            val bindingListener: (Int?, Int?) -> Unit = { previous, current ->
                bindingChanges += previous to current
            }

            viewModel.listenState(directListener)
            binding.listenState(spec, bindingListener)
            viewModel.setState(null)
            viewModel.setState(1)
            viewModel.setState(null)
            viewModel.setState(2)

            assertEquals(listOf(null to 1, 1 to null, null to 2), directChanges)
            assertEquals(directChanges, bindingChanges)
            assertNull(viewModel.previousState)
            assertEquals(2, viewModel.state)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun selectorOverloads_acceptNonNullableCallbacksAndCompareActualTransitions() {
        val binding = ViewModelBinding()
        try {
            val spec = viewModelSpec { StateViewModel(CallbackState()) }
            val viewModel by binding.readViewModel(spec)
            val directDefaultChanges = mutableListOf<Pair<Int, Int>>()
            val directCustomChanges = mutableListOf<Pair<Int, Int>>()
            val bindingDefaultChanges = mutableListOf<Pair<Int, Int>>()
            val bindingCustomChanges = mutableListOf<Pair<Int, Int>>()
            val selector: (CallbackState) -> Int = { it.count }
            val parityEquals: (Int, Int) -> Boolean = { previous, current ->
                previous % 2 == current % 2
            }
            val directDefaultListener: (Int, Int) -> Unit = { previous, current ->
                directDefaultChanges += previous to current
            }
            val directCustomListener: (Int, Int) -> Unit = { previous, current ->
                directCustomChanges += previous to current
            }
            val bindingDefaultListener: (Int, Int) -> Unit = { previous, current ->
                bindingDefaultChanges += previous to current
            }
            val bindingCustomListener: (Int, Int) -> Unit = { previous, current ->
                bindingCustomChanges += previous to current
            }

            viewModel.listenStateSelect(selector, directDefaultListener)
            viewModel.listenStateSelect(selector, parityEquals, directCustomListener)
            binding.listenStateSelect(spec, selector, bindingDefaultListener)
            binding.listenStateSelect(spec, selector, parityEquals, bindingCustomListener)

            viewModel.setState(CallbackState(label = "same selected value"))
            viewModel.setState(CallbackState(count = 2))
            viewModel.setState(CallbackState(count = 3))
            viewModel.setState(CallbackState(count = 5))
            viewModel.setState(CallbackState(count = 6))

            assertEquals(listOf(0 to 2, 2 to 3, 3 to 5, 5 to 6), directDefaultChanges)
            assertEquals(listOf(2 to 3, 5 to 6), directCustomChanges)
            assertEquals(directDefaultChanges, bindingDefaultChanges)
            assertEquals(directCustomChanges, bindingCustomChanges)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun nullableStateSelector_receivesNullAndCanReturnNonNullableValues() {
        val binding = ViewModelBinding()
        try {
            val spec = viewModelSpec { StateViewModel<Int?>(null) }
            val viewModel by binding.readViewModel(spec)
            val directChanges = mutableListOf<Pair<Int, Int>>()
            val bindingChanges = mutableListOf<Pair<Int, Int>>()
            val selector: (Int?) -> Int = { it ?: -1 }
            val directListener: (Int, Int) -> Unit = { previous, current ->
                directChanges += previous to current
            }
            val bindingListener: (Int, Int) -> Unit = { previous, current ->
                bindingChanges += previous to current
            }

            viewModel.listenStateSelect(selector, directListener)
            binding.listenStateSelect(spec, selector, bindingListener)
            viewModel.setState(2)
            viewModel.setState(null)

            assertEquals(listOf(-1 to 2, 2 to -1), directChanges)
            assertEquals(directChanges, bindingChanges)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun nullableSelectorResult_preservesNullOnBothSidesAndSkipsEqualValues() {
        val binding = ViewModelBinding()
        try {
            val spec = viewModelSpec { StateViewModel(CallbackState()) }
            val viewModel by binding.readViewModel(spec)
            val directChanges = mutableListOf<Pair<String?, String?>>()
            val bindingChanges = mutableListOf<Pair<String?, String?>>()
            val selector: (CallbackState) -> String? = { it.label }
            val directListener: (String?, String?) -> Unit = { previous, current ->
                directChanges += previous to current
            }
            val bindingListener: (String?, String?) -> Unit = { previous, current ->
                bindingChanges += previous to current
            }

            viewModel.listenStateSelect(selector, directListener)
            binding.listenStateSelect(spec, selector, null, bindingListener)
            viewModel.setState(CallbackState(count = 1))
            viewModel.setState(CallbackState(count = 2, label = "ready"))
            viewModel.setState(CallbackState(count = 3, label = "ready"))
            viewModel.setState(CallbackState(count = 4))

            assertEquals(listOf(null to "ready", "ready" to null), directChanges)
            assertEquals(directChanges, bindingChanges)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun reentrantStateChanges_keepStateAndSelectorPairsInTransitionOrder() {
        val binding = ViewModelBinding()
        try {
            val spec = viewModelSpec { StateViewModel(0) }
            val viewModel by binding.readViewModel(spec)
            val calls = mutableListOf<String>()
            viewModel.listenState { _, current ->
                if (current == 1) viewModel.setState(2)
            }
            viewModel.listenState { previous: Int, current: Int ->
                calls += "state: $previous -> $current"
            }
            viewModel.listenStateSelect(selector = { it * 10 }) { previous: Int, current: Int ->
                calls += "selected: $previous -> $current"
            }

            viewModel.setState(1)

            assertEquals(
                listOf(
                    "state: 0 -> 1",
                    "selected: 0 -> 10",
                    "state: 1 -> 2",
                    "selected: 10 -> 20",
                ),
                calls,
            )
            assertEquals(1, viewModel.previousState)
            assertEquals(2, viewModel.state)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun stateAndSelectorDisposers_skipPendingCallbacksAndRemainIdempotent() {
        val binding = ViewModelBinding()
        try {
            val spec = viewModelSpec { StateViewModel(0) }
            val viewModel by binding.readViewModel(spec)
            val calls = mutableListOf<String>()
            var removeStateListener: () -> Unit = {}
            var removeSelectorListener: () -> Unit = {}
            viewModel.listenState { _, _ ->
                calls += "first"
                removeStateListener()
                removeSelectorListener()
            }
            removeStateListener = viewModel.listenState { _, _ -> calls += "state" }
            removeSelectorListener = viewModel.listenStateSelect(selector = { it }) { _, _ ->
                calls += "selected"
            }

            viewModel.setState(1)
            removeStateListener()
            removeSelectorListener()
            viewModel.setState(2)

            assertEquals(listOf("first", "first"), calls)
        } finally {
            binding.dispose()
        }
    }
}

private data class CallbackState(
    val count: Int = 0,
    val label: String? = null,
)
