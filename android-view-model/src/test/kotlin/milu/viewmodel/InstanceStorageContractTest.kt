package milu.viewmodel

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class InstanceStorageContractTest {
    @Before fun setUp() = ViewModel.reset()
    @After fun tearDown() = ViewModel.reset()

    @Test fun directAndPropagatedSourcesReleaseIndependently() {
        val probe = LifecycleProbe()
        val handle = InstanceHandle(probe, InstanceArg(key = "probe"), 0)
        val first = Any()
        val second = Any()
        handle.bind(null)
        handle.bindFrom(null, first)
        handle.bind("a")
        handle.bind("a")
        handle.bindFrom("a", first)
        handle.bindFrom("a", first)
        handle.bindFrom("a", second)
        handle.bind("b")
        assertSame(probe, handle.requireInstance())
        assertTrue(handle.contains("a"))
        assertTrue(handle.containsDirectBinding("a"))
        assertEquals(listOf("a", "b"), handle.bindingIds)
        handle.unbindFrom("missing", first)
        handle.unbindFrom("a", Any())
        handle.unbind("a")
        handle.unbind("a")
        assertFalse(handle.containsDirectBinding("a"))
        assertTrue(handle.contains("a"))
        handle.unbindFrom("a", first)
        handle.unbindFrom("a", second)
        assertFalse(handle.contains("a"))
        assertFalse(handle.isDisposed)
        handle.unbind("b")
        assertTrue(handle.isDisposed)
        assertNull(handle.value)
        assertThrows(ViewModelError::class.java) { handle.requireInstance() }
        handle.bind("late")
        handle.bindFrom("late", first)
        handle.unbindFrom("a", first)
        handle.unbindAll()
        assertEquals(listOf("create", "bind:a", "bind:b", "unbind:a", "unbind:b", "dispose"), probe.events)
    }

    @Test fun retainedNonViewModelOwnershipIsIdempotentAndForceDisposalRejectsReentry() {
        val owner = ViewModelBinding()
        val other = ViewModelBinding()
        val probe = LifecycleProbe()
        val handle = InstanceHandle(probe, InstanceArg(key = "retained", aliveForever = true), 0, false)
        val source = Any()
        try {
            handle.bindOwner(owner)
            handle.bindOwner(owner)
            handle.bindOwner(owner, source)
            handle.unbindOwner(other)
            handle.unbindOwner(owner, Any())
            handle.unbindOwner(owner)
            assertEquals(listOf(owner.id), handle.bindingIds)
            handle.unbindOwner(owner, source)
            assertTrue(handle.bindingIds.isEmpty())
            assertFalse(handle.isDisposed)
            handle.unbindAll()
            assertFalse(handle.isDisposed)
            handle.bindOwner(owner)
            var recycled = false
            val cancel = handle.addListener { error("cancelled listener") }
            cancel()
            handle.addListener {
                assertTrue(it.isRecycling)
                it.bind("late")
                it.bindFrom("late", source)
                assertThrows(ViewModelError::class.java) { it.bindOwner(other) }
                it.unbindAll(true)
                recycled = true
            }
            handle.unbindAll(true)
            assertTrue(recycled)
            assertTrue(handle.isDisposed)
            handle.unbindOwner(owner)
            assertThrows(ViewModelError::class.java) { handle.bindOwner(owner) }
            val live = InstanceHandle("plain", InstanceArg(), 1)
            other.dispose()
            assertThrows(ViewModelError::class.java) { live.bindOwner(other) }
            live.unbindAll()
        } finally {
            handle.unbindAll(true)
            owner.dispose()
            other.dispose()
        }
    }

    @Test fun lifecycleFailuresAreReportedAndCancelledDisposalListenersAreSkipped() {
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(ViewModelConfig(onError = { _, type -> errors += type }))
        val probe = LifecycleProbe(fail = true)
        val handle = InstanceHandle(probe, InstanceArg(bindingId = "owner"), 0)
        var remove: () -> Unit = {}
        handle.addListener { remove() }
        remove = handle.addListener { error("removed before dispatch") }
        handle.unbindAll()
        assertTrue(handle.isDisposed)
        assertEquals(listOf(ErrorType.Lifecycle, ErrorType.Lifecycle, ErrorType.Lifecycle, ErrorType.Dispose), errors)
    }

    @Test fun storeDefaultsKeyReuseTagOrderingAndTerminalQueries() {
        var emptyCalls = 0
        val store = Store(String::class) { emptyCalls++ }
        val noCallback = Store(String::class)
        try {
            val first = store.getHandle(InstanceFactory({ "first" }, InstanceArg(key = "one", tag = "tag", bindingId = "a")))
            assertSame(first, store.getHandle(InstanceFactory(arg = InstanceArg(key = "one", bindingId = "b"))))
            val second = store.getHandle(InstanceFactory({ "second" }, InstanceArg(key = "two", tag = "tag")))
            val unkeyed = store.getHandle(InstanceFactory({ "unkeyed" }))
            assertEquals(listOf(second, first), store.instancesByTag("tag"))
            assertEquals(3, store.countInstances())
            assertEquals(2, store.countInstances("tag"))
            assertEquals(0, store.countInstances("missing"))
            assertSame(unkeyed, store.findNewest())
            assertSame(second, store.findNewest("tag"))
            assertNull(store.findNewest("missing"))
            assertFalse(store.tryRecycle("unknown"))
            assertTrue(store.tryRecycle(unkeyed.requireInstance()))
            assertTrue(store.tryRecycle(second.requireInstance()))
            assertTrue(store.tryRecycle(first.requireInstance()))
            assertEquals(1, emptyCalls)
            assertTrue(store.isEmpty)
            assertThrows(ViewModelError::class.java) {
                store.getHandle(InstanceFactory({ "invalid" }, InstanceArg(aliveForever = true)))
            }
            val plain = noCallback.getHandle(InstanceFactory({ "without callback" }))
            plain.unbindAll()
            assertTrue(noCallback.isEmpty)
            store.dispose()
            assertTrue(store.instancesByTag("tag").isEmpty())
            assertFalse(store.tryRecycle("first"))
            assertThrows(ViewModelError::class.java) { store.findNewest() }
            assertThrows(ViewModelError::class.java) { store.getHandle(InstanceFactory({ "late" })) }
        } finally {
            store.dispose()
            noCallback.dispose()
        }
    }

    @Test fun storeDisposalDuringBuilderAndCreateDisposesTentativeInstances() {
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(ViewModelConfig(onError = { _, type -> errors += type }))
        val plainStore = Store(String::class)
        assertThrows(ViewModelError::class.java) {
            plainStore.getHandle(InstanceFactory({ plainStore.dispose(); "plain" }))
        }
        val failingStore = Store(LifecycleProbe::class)
        val probe = LifecycleProbe(fail = true)
        assertThrows(ViewModelError::class.java) {
            failingStore.getHandle(InstanceFactory({ failingStore.dispose(); probe }))
        }
        assertEquals(listOf(ErrorType.Dispose), errors)
        val createStore = Store(LifecycleProbe::class)
        val created = LifecycleProbe(onCreated = { createStore.dispose() })
        assertThrows(ViewModelError::class.java) {
            createStore.getHandle(InstanceFactory({ created }))
        }
        assertEquals(listOf("create", "dispose"), created.events)
    }

    @Test fun rawRegistryLookupsKeepOptionalFactoryAndCallbackSemantics() {
        val type = InstanceType(String::class)
        assertNull(InstanceManager.maybeGet(type))
        assertThrows(ViewModelError::class.java) { InstanceManager.get(String::class) }
        val first = InstanceManager.get(String::class, InstanceFactory({ "one" }, InstanceArg(key = "one", tag = "tag")))
        assertEquals("one", first)
        assertEquals("one", InstanceManager.get(type))
        assertEquals("one", InstanceManager.maybeGet(type))
        var resolved = 0
        InstanceManager.getHandle(type, onResolve = { resolved++ })
        InstanceManager.getHandle(type, InstanceFactory(arg = InstanceArg(key = "missing", tag = "tag")), onResolve = { resolved++ })
        assertEquals(2, resolved)
        assertThrows(ViewModelError::class.java) {
            InstanceManager.getHandle(type, InstanceFactory(arg = InstanceArg(key = "missing", tag = "absent")))
        }
        val newest = InstanceManager.getHandle(type, InstanceFactory(arg = InstanceArg(bindingId = "reader")))
        assertTrue(newest.contains("reader"))
        assertThrows(ViewModelError::class.java) { InstanceManager.recycle(42) }
        newest.unbindAll()
        assertNull(InstanceManager.maybeGet(type, InstanceFactory(arg = InstanceArg(key = "one"))))
        InstanceManager.debugReset()
    }

    @Test fun typeIdentityRejectsNullableMismatchedAndNonConcreteTypes() {
        assertThrows(ViewModelError::class.java) { InstanceType(String::class, typeOf<Int>()) }
        assertThrows(ViewModelError::class.java) { InstanceType(String::class, typeOf<String?>()) }
        val typeParameter = object : KType {
            override val annotations = emptyList<Annotation>()
            override val arguments = emptyList<kotlin.reflect.KTypeProjection>()
            override val classifier = null
            override val isMarkedNullable = false
        }
        assertThrows(ViewModelError::class.java) { instanceTypeOf<Any>(typeParameter) }
        val raw = InstanceType(String::class)
        val typed = InstanceType(String::class, typeOf<String>())
        assertEquals(raw, typed)
        assertEquals(raw.hashCode(), typed.hashCode())
        assertNotEquals(raw, InstanceType(Int::class))
        assertNotEquals(raw, "string")
        assertEquals(raw.description, raw.toString())
        assertTrue(typed.toString().contains("String"))
        val anonymous = object {}
        val anonymousType = InstanceType(anonymous::class, object : KType {
            override val annotations = emptyList<Annotation>()
            override val arguments = emptyList<kotlin.reflect.KTypeProjection>()
            override val classifier = anonymous::class
            override val isMarkedNullable = false
        })
        assertNotNull(anonymousType.description)
    }
}

private class LifecycleProbe(
    private val fail: Boolean = false,
    private val onCreated: () -> Unit = {},
) : InstanceLifeCycle {
    val events = mutableListOf<String>()
    override fun onCreate(arg: InstanceArg) { events += "create"; onCreated(); if (fail) error("create") }
    override fun onBind(arg: InstanceArg, bindingId: String) { events += "bind:$bindingId"; if (fail) error("bind") }
    override fun onUnbind(arg: InstanceArg, bindingId: String) { events += "unbind:$bindingId"; if (fail) error("unbind") }
    override fun onDispose(arg: InstanceArg) { events += "dispose"; if (fail) error("dispose") }
}
