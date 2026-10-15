package milu.viewmodel

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ControllerFailureContractTest {
    @Before fun setUp() = ViewModel.reset()
    @After fun tearDown() = ViewModel.reset()

    @Test fun retainedGenerationUnboundDuringAttachmentIsRejectedAndDisposed() {
        val owner = ViewModelBinding()
        var disposals = 0
        val spec = viewModelSpec(key = "released-in-bind", aliveForever = true) {
            object : ViewModel() {
                override fun onBind(arg: InstanceArg, bindingId: String) {
                    super.onBind(arg, bindingId)
                    owner.unbind(this)
                }
                override fun dispose() { disposals++ }
            }
        }
        try {
            val vm by owner.readViewModel(spec)
            val failure = assertThrows(ViewModelError::class.java) { vm }
            assertTrue(failure.message!!.contains("Ownership was released"))
            assertEquals(1, disposals)
            assertEquals(0, InstanceManager.debugStoreCount)
        } finally { owner.dispose() }
    }

    @Test fun controllerReportsDetachmentFailuresAndContinuesDisposingOtherHandles() {
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(ViewModelConfig(onError = { _, type -> errors += type }))
        val owner = ViewModelBinding()
        val controller = AutoDisposeInstanceController(owner, {}, onInstanceDetached = { _, vm ->
            if (vm.tag == "bad") error("detach callback")
            null
        })
        val bad = viewModelSpec(key = "bad", tag = "bad") { ViewModel() }
        val good = viewModelSpec(key = "good", tag = "good") { ViewModel() }
        val badHandle = controller.getHandle(bad.instanceType, InstanceFactory(bad::build, InstanceArg(key = "bad", tag = "bad")))
        val goodHandle = controller.getHandle(good.instanceType, InstanceFactory(good::build, InstanceArg(key = "good", tag = "good")))
        try {
            controller.dispose()
            controller.dispose()
            assertTrue(goodHandle.isDisposed)
            assertEquals(listOf(ErrorType.Dispose), errors)
            assertThrows(ViewModelError::class.java) {
                controller.getInstancesByTag(bad.instanceType, "bad")
            }
        } finally {
            badHandle.unbindAll(true)
            goodHandle.unbindAll(true)
            controller.dispose()
            owner.dispose()
        }
    }

    @Test fun recyclingReportsDetachmentFailureAndStillDisposesTheGeneration() {
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(ViewModelConfig(onError = { _, type -> errors += type }))
        val owner = ViewModelBinding()
        val controller = AutoDisposeInstanceController(owner, {}, onInstanceDetached = { _, _ -> error("detach callback") })
        val spec = viewModelSpec { ViewModel() }
        val handle = controller.getHandle(spec.instanceType, InstanceFactory(spec::build))
        try {
            handle.unbindAll(true)
            assertTrue(handle.isDisposed)
            assertEquals(listOf(ErrorType.Listener), errors)
        } finally {
            controller.dispose()
            owner.dispose()
        }
    }

    @Test fun reentrantDisposalRemovesAnotherHandleBeforeItsSnapshotTurn() {
        val owner = ViewModelBinding()
        val handles = mutableListOf<InstanceHandle<*>>()
        val detached = mutableListOf<InstanceHandle<*>>()
        var reentered = false
        val controller = AutoDisposeInstanceController(owner, {}, onInstanceDetached = { handle, _ ->
            detached += handle
            if (!reentered) {
                reentered = true
                handles.filterNot { it === handle }.forEach { it.unbindAll(true) }
            }
            null
        })
        try {
            for (key in listOf("first", "second")) {
                val spec = viewModelSpec(key = key) { ViewModel() }
                handles += controller.getHandle(spec.instanceType, InstanceFactory(spec::build, InstanceArg(key = key)))
            }
            // Whichever IdentityHashMap entry is visited first disposes the
            // other one; its later snapshot entry must be an idempotent no-op.
            controller.dispose()
            assertEquals(2, detached.size)
            assertEquals(2, detached.toSet().size)
            assertTrue(handles.all { it.isDisposed })
            assertEquals(0, InstanceManager.debugStoreCount)
        } finally {
            controller.dispose()
            owner.dispose()
        }
    }

    @Test fun controllerCanOwnPlainValuesWithoutViewModelCallbacks() {
        val owner = ViewModelBinding()
        var refreshes = 0
        val controller = AutoDisposeInstanceController(owner, { refreshes++ })
        try {
            val type = InstanceType(String::class)
            val first = controller.getHandle(type, InstanceFactory({ "plain" }, InstanceArg(key = "plain", tag = "tag")))
            assertEquals("plain", controller.getInstance(type, InstanceFactory(arg = InstanceArg(key = "plain"))))
            assertEquals(listOf("plain"), controller.getInstancesByTag(type, "tag"))
            first.unbindAll(true)
            assertEquals(1, refreshes)
            controller.unbind("plain")
        } finally {
            controller.dispose()
            owner.dispose()
        }
    }
}
