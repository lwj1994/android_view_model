package milu.viewmodel

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OwnerDiagnosticsContractTest {
    private val bindings = mutableListOf<ViewModelBinding>()
    private fun binding() = ViewModelBinding().also(bindings::add)
    @Before fun setUp() = ViewModel.reset()
    @After fun tearDown() {
        bindings.asReversed().forEach { it.dispose() }
        ViewModel.reset()
    }

    @Test fun standaloneLedgerPublishesOnlyFirstAndLastSourceChanges() {
        val handler = ViewModelBindingHandler()
        val first = binding()
        val second = binding()
        val source = Any()
        val notifications = mutableListOf<List<String>>()
        handler.addOwnerChangeListener { owners, _, _ -> notifications += owners }
        assertNull(handler.clearRefs())
        assertNull(handler.removeRef(first))
        assertFalse(handler.hasDirectRef(first))
        handler.addRef(first)
        assertSame(first, handler.binding)
        assertEquals(listOf(first), handler.constructionExternalOwners)
        assertNull(handler.addRef(first))
        assertNull(handler.addRef(first, source))
        assertNull(handler.removeRef(first, Any()))
        assertNull(handler.removeRef(first))
        assertFalse(handler.hasDirectRef(first))
        handler.addRef(second)
        handler.removeRef(first, source)
        assertSame(second, handler.binding)
        handler.clearRefs()
        assertEquals(listOf(listOf(first.id), listOf(first.id, second.id), listOf(second.id), emptyList<String>()), notifications)
        assertThrows(ViewModelError::class.java) { handler.binding }
        handler.dispose()
    }

    @Test fun diagnosticsListenerFailuresAndCancellationDoNotBreakCommittedOwners() {
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(ViewModelConfig(onError = { _, type -> errors += type }))
        val handler = ViewModelBindingHandler()
        val owner = binding()
        var remove: () -> Unit = {}
        handler.addOwnerChangeListener { _, _, _ -> remove(); error("diagnostics") }
        remove = handler.addOwnerChangeListener { _, _, _ -> error("cancelled") }
        var notified = 0
        handler.addOwnerChangeListener { _, _, _ -> notified++ }
        handler.addRef(owner)
        assertEquals(listOf(owner), handler.owners)
        assertEquals(1, notified)
        assertEquals(listOf(ErrorType.Listener), errors)
        handler.dispose()
    }

    @Test fun constructionFallbackRejectsDisposedAndDependencyOwners() {
        val handler = ViewModelBindingHandler()
        assertTrue(handler.constructionExternalOwners.isEmpty())
        val owner = binding()
        ViewModelBinding.withBuilding(owner) {
            assertSame(owner, handler.binding)
            assertEquals(listOf(owner), handler.constructionExternalOwners)
        }
        owner.dispose()
        ViewModelBinding.withBuilding(owner) { assertTrue(handler.constructionExternalOwners.isEmpty()) }
        val dependency = object : ViewModelBinding() { override val isDependencyBinding = true }
        try {
            ViewModelBinding.withBuilding(dependency) { assertTrue(handler.constructionExternalOwners.isEmpty()) }
        } finally { dependency.dispose() }
    }

    @Test fun rawCompatibilityCachedEntryPointsMatchReifiedLookupAndOwnership() {
        val owner = binding()
        val reader = binding()
        val spec = viewModelSpec(key = "raw-cache", tag = "tag") { ViewModel() }
        val vm by owner.readViewModel(spec)
        assertFalse(vm.isDisposed)
        assertSame(vm, ViewModel.readCached(ViewModel::class, "raw-cache", null))
        assertEquals(listOf(vm), reader.readCachesByTag(ViewModel::class, "tag"))
        assertEquals(listOf(vm), reader.watchCachesByTag(ViewModel::class, "tag"))
        assertSame(vm, reader.requireExistingViewModel(ViewModel::class, InstanceArg(key = "raw-cache"), false))
        assertSame(vm, reader.requireExistingViewModel(ViewModel::class, InstanceArg(key = "raw-cache"), true))
        assertEquals(listOf(owner, reader), vm.refHandler.owners)
    }

    @Test fun constructionRollbackCallbacksCannotRegisterResourcesOnCompletedTransaction() {
        val events = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            runInViewModelConstruction(InstanceType(String::class), "key", false) {
                registerViewModelConstructionRollback {
                    events += "rollback"
                    registerViewModelConstructionRollback { events += "late" }
                }
                error("construction failed")
            }
        }
        assertEquals(listOf("rollback"), events)
        val owner = binding()
        assertTrue(markViewModelBindingUpdated(owner))
        runInViewModelUpdateTransaction {
            assertTrue(markViewModelBindingUpdated(owner))
            assertFalse(markViewModelBindingUpdated(owner))
        }
        assertTrue(markViewModelBindingUpdated(owner))
    }
}
