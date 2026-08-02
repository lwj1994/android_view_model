package milu.viewmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ViewModelCoreTest {
    @Before
    fun setUp() {
        ViewModel.reset()
    }

    @After
    fun tearDown() {
        ViewModel.reset()
    }

    @Test
    fun notifyListeners_firesEveryRegisteredCallback() {
        val spec = viewModelSpec { CounterViewModel() }
        val binding = ViewModelBinding()
        try {
            val vm = binding.read(spec)
            var a = 0
            var b = 0
            vm.listen { a += 1 }
            vm.listen { b += 1 }

            vm.notifyListeners()

            assertEquals(1, a)
            assertEquals(1, b)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun update_triggersNotifyListenersOnce() {
        val spec = viewModelSpec { CounterViewModel() }
        val binding = ViewModelBinding()
        try {
            val vm = binding.read(spec)
            var fired = 0
            vm.listen { fired += 1 }

            vm.increment()

            assertEquals(1, vm.count)
            assertEquals(1, fired)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun sharedKey_usesSameInstanceAcrossBindings() {
        val spec = viewModelSpec(key = "shared") { CounterViewModel() }
        val first = ViewModelBinding()
        val second = ViewModelBinding()

        val a = first.watch(spec)
        val b = second.watch(spec)

        assertSame(a, b)
        assertEquals(1, a.onCreateCalls)
        assertEquals(2, a.onBindCalls)

        first.dispose()
        assertFalse(a.isDisposed)

        second.dispose()
        assertTrue(a.isDisposed)
        assertEquals(2, a.onUnbindCalls)
        assertEquals(1, a.onDisposeCalls)
    }

    @Test
    fun unkeyedSpec_reusesOneInstanceWithinBinding() {
        val spec = viewModelSpec { CounterViewModel() }
        val binding = ViewModelBinding()

        val first = binding.read(spec)
        val second = binding.read(spec)

        assertSame(first, second)
        binding.dispose()
        assertTrue(first.isDisposed)
    }

    @Test
    fun aliveForever_survivesLastBindingDispose() {
        val spec = viewModelSpec(key = "forever", aliveForever = true) { CounterViewModel() }
        val binding = ViewModelBinding()
        val vm = binding.read(spec)

        binding.dispose()

        assertFalse(vm.isDisposed)
        val next = ViewModelBinding().read(spec)
        assertSame(vm, next)
    }

    @Test
    fun readCached_returnsExistingInstance() {
        val spec = viewModelSpec(key = "cache") { CounterViewModel() }
        val binding = ViewModelBinding()
        val vm = binding.read(spec)

        val cached = binding.readCached<CounterViewModel>(key = "cache")

        assertSame(vm, cached)
        binding.dispose()
    }

    @Test
    fun bindingDispose_cascadesToDependencyCreatedInInit() {
        RootViewModel.depSpec = viewModelSpec { DependencyViewModel() }
        val rootSpec = viewModelSpec { RootViewModel() }
        val binding = ViewModelBinding()

        val root = binding.watch(rootSpec)
        val dep = root.dep

        assertNotNull(dep)
        binding.dispose()
        assertTrue(root.isDisposed)
        assertTrue(dep.isDisposed)
    }

    @Test
    fun stateViewModel_firesStateAndGeneralListeners() {
        val spec = viewModelSpec { CounterStateViewModel() }
        val binding = ViewModelBinding()
        try {
            val vm = binding.read(spec)
            val states = mutableListOf<Pair<CounterState?, CounterState>>()
            var generalFired = 0
            vm.listenState { previous, current -> states += previous to current }
            vm.listen { generalFired += 1 }

            vm.increment()
            vm.increment()

            assertEquals(2, states.size)
            assertEquals(0, states[0].first?.count)
            assertEquals(1, states[0].second.count)
            assertEquals(1, states[1].first?.count)
            assertEquals(2, states[1].second.count)
            assertEquals(2, generalFired)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun listenStateSelect_onlyFiresWhenSelectedValueChanges() {
        val spec = viewModelSpec { CounterStateViewModel() }
        val binding = ViewModelBinding()
        try {
            val vm = binding.read(spec)
            var labelChanges = 0
            vm.listenStateSelect(selector = { it.label }) { _, _ -> labelChanges += 1 }

            vm.increment()
            vm.increment()
            vm.setLabel("hello")

            assertEquals(1, labelChanges)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun proxy_replacesSpecBuilder() {
        val spec = viewModelSpec(key = "proxy") { CounterViewModel(label = "real") }
        spec.setProxy(viewModelSpec(key = "proxy") { CounterViewModel(label = "proxy") })

        val vm = ViewModelBinding().read(spec)

        assertEquals("proxy", vm.label)
    }
}

private class CounterViewModel(
    val label: String = "",
) : ViewModel() {
    var count = 0
    var onCreateCalls = 0
    var onBindCalls = 0
    var onUnbindCalls = 0
    var onDisposeCalls = 0

    fun increment() {
        update { count += 1 }
    }

    override fun onCreate(arg: InstanceArg) {
        super.onCreate(arg)
        onCreateCalls += 1
    }

    override fun onBind(
        arg: InstanceArg,
        bindingId: String,
    ) {
        super.onBind(arg, bindingId)
        onBindCalls += 1
    }

    override fun onUnbind(
        arg: InstanceArg,
        bindingId: String,
    ) {
        super.onUnbind(arg, bindingId)
        onUnbindCalls += 1
    }

    override fun onDispose(arg: InstanceArg) {
        onDisposeCalls += 1
        super.onDispose(arg)
    }
}

private class DependencyViewModel : ViewModel()

private class RootViewModel : ViewModel() {
    companion object {
        lateinit var depSpec: ViewModelSpec<DependencyViewModel>
    }

    val dep: DependencyViewModel
        get() = viewModelBinding.read(depSpec)
}

private data class CounterState(
    val count: Int = 0,
    val label: String = "",
)

private class CounterStateViewModel : StateViewModel<CounterState>(
    initialState = CounterState(),
    equals = { previous, current -> previous == current },
) {
    fun increment() {
        setState(state.copy(count = state.count + 1))
    }

    fun setLabel(label: String) {
        setState(state.copy(label = label))
    }
}
