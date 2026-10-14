package milu.viewmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OwnershipCommitTest {
    private val events = mutableListOf<String>()
    private val errors = mutableListOf<Throwable>()
    private val owners = mutableListOf<CommitOwner>()

    @Before
    fun setUp() {
        ViewModel.reset()
        ViewModel.initialize(config = ViewModelConfig(onError = { error, _ -> errors += error }))
    }

    @After
    fun tearDown() {
        owners.forEach { it.dispose() }
        ViewModel.reset()
        assertEquals("Lifecycle errors must not hide failures", emptyList<Throwable>(), errors)
    }

    private fun spec(
        name: String,
        tag: Any? = null,
        retained: Boolean = false,
        construct: ((CommitNode) -> Unit)? = null,
        created: ((CommitNode) -> Unit)? = null,
        bound: ((CommitNode, String) -> Unit)? = null,
        unbound: ((CommitNode, String) -> Unit)? = null,
    ) = viewModelSpec(key = name, tag = tag, aliveForever = retained) {
        CommitNode(name, events, construct, created, bound, unbound)
    }

    private fun owner(spec: ViewModelSpec<CommitNode>) = CommitOwner(spec).also { owners += it }

    private fun handle(key: String) = InstanceManager.getHandle(
        InstanceType(CommitNode::class), InstanceFactory(arg = InstanceArg(key = key)),
    )

    @Test
    fun lifecycleSeesCommittedOwnersAndReadPreservesWatch() {
        val snapshots = mutableListOf<Triple<String, List<String>, List<String>>>()
        fun snapshot(vm: CommitNode, phase: String) {
            snapshots += Triple(phase, handle("node").bindingIds, vm.refHandler.owners.map { it.id })
        }
        val owner = owner(spec("node", bound = { vm, _ -> snapshot(vm, "bind") },
            unbound = { vm, _ -> snapshot(vm, "unbind") }))
        owner.vm
        owner.watched
        owner.watched
        owner.vm.notifyListeners()
        assertEquals(1, owner.updates)
        owner.dispose()
        assertEquals(listOf(
            Triple("bind", listOf(owner.id), listOf(owner.id)),
            Triple("unbind", emptyList<String>(), emptyList<String>()),
        ), snapshots)
        assertEquals(listOf("create:node", "bind:node:${owner.id}", "unbind:node:${owner.id}",
            "dispose:node", "cleanup:node"), events)
    }

    @Test
    fun childOnUnbindReentrySuppressesObsoleteParentOnUnbind() {
        lateinit var owner: CommitOwner
        var rebind = true
        var active = false
        owner = owner(spec("parent", bound = { _, id ->
            if (id == owner.id) active = true
        }, unbound = { _, id ->
            if (id == owner.id) active = false
        }))
        val childSpec = spec("child", unbound = { _, id ->
            if (id == owner.id && rebind) {
                rebind = false
                owner.watched
            }
        })
        owner.watched.child(childSpec, watch = true)
        val generation = owner.vm
        events.clear()

        owner.unbind(owner.vm)

        assertEquals(listOf("unbind:child:${owner.id}", "bind:parent:${owner.id}",
            "bind:child:${owner.id}"), events)
        assertTrue(active)
        assertEquals(listOf(owner.id), handle("parent").bindingIds)
        assertEquals(listOf(owner), owner.vm.refHandler.owners)
        assertTrue(generation === owner.vm)
        owner.vm.child(childSpec).notifyListeners()
        assertEquals(1, owner.updates)
        owner.dispose()
        assertFalse(active)
        assertEquals(1, events.count { it == "unbind:parent:${owner.id}" })
        assertEquals(2, events.count { it.startsWith("dispose:") })
    }

    @Test
    fun childOnUnbindRebindAndReleaseDoesNotDuplicateParentUnbind() {
        val parentSpec = spec("parent")
        val keeper = owner(parentSpec)
        val releasing = owner(parentSpec)
        var rebind = true
        val childSpec = spec("child", unbound = { _, id ->
            if (id == releasing.id && rebind) {
                rebind = false
                releasing.watched
                releasing.unbind(releasing.vm)
            }
        })
        keeper.vm.child(childSpec)
        val generation = releasing.watched
        events.clear()

        releasing.unbind(generation)

        assertFalse(generation.isDisposed)
        assertEquals(listOf(keeper), generation.refHandler.owners)
        assertEquals(1, events.count { it == "unbind:parent:${releasing.id}" })
    }

    @Test
    fun childOnUnbindRecyclePreservesPendingParentUnbind() {
        assertChildOnUnbindDisposalPreservesPendingParentUnbind(reset = false)
    }

    @Test
    fun childOnUnbindResetPreservesPendingParentUnbind() {
        assertChildOnUnbindDisposalPreservesPendingParentUnbind(reset = true)
    }

    private fun assertChildOnUnbindDisposalPreservesPendingParentUnbind(reset: Boolean) {
        val parent = owner(spec("parent"))
        val generation = parent.vm
        val childSpec = spec("child", unbound = { _, id ->
            if (id == parent.id) {
                if (reset) ViewModel.reset() else parent.recycle(generation)
            }
        })
        generation.child(childSpec)
        events.clear()

        parent.unbind(generation)

        assertTrue(generation.isDisposed)
        assertEquals(1, events.count { it == "unbind:parent:${parent.id}" })
        assertTrue(events.indexOf("unbind:parent:${parent.id}") < events.indexOf("dispose:parent"))
    }

    @Test
    fun childOnUnbindReleasingLastKeeperPreservesPendingParentUnbind() {
        assertChildOnUnbindReleasingLastKeeper(reacquire = false)
    }

    @Test
    fun pendingParentOnUnbindCanReacquireBeforeAutomaticRecycle() {
        assertChildOnUnbindReleasingLastKeeper(reacquire = true)
    }

    private fun assertChildOnUnbindReleasingLastKeeper(reacquire: Boolean) {
        lateinit var releasing: CommitOwner
        var rebind = reacquire
        val parentSpec = spec("parent", unbound = { _, id ->
            if (id == releasing.id && rebind) {
                rebind = false
                releasing.watched
            }
        })
        val keeper = owner(parentSpec)
        releasing = owner(parentSpec)
        var releaseKeeper = true
        val childSpec = spec("child", unbound = { _, id ->
            if (id == releasing.id && releaseKeeper) {
                releaseKeeper = false
                keeper.unbind(keeper.vm)
            }
        })
        keeper.vm.child(childSpec)
        val generation = releasing.watched
        events.clear()

        releasing.unbind(generation)

        assertEquals(1, events.count { it == "unbind:parent:${releasing.id}" })
        assertEquals(1, events.count { it == "unbind:parent:${keeper.id}" })
        assertEquals(!reacquire, generation.isDisposed)
        if (reacquire) {
            assertTrue(generation === releasing.vm)
            assertEquals(listOf(releasing), generation.refHandler.owners)
            generation.notifyListeners()
            assertEquals(1, releasing.updates)
        } else {
            assertTrue(events.indexOf("unbind:parent:${releasing.id}") < events.indexOf("dispose:parent"))
        }
    }

    @Test
    fun pendingParentOnUnbindCanForceRecycleDuringAutomaticRecycle() {
        lateinit var releasing: CommitOwner
        val parentSpec = spec("parent", unbound = { vm, id ->
            if (id == releasing.id) releasing.recycle(vm)
        })
        val keeper = owner(parentSpec)
        releasing = owner(parentSpec)
        val childSpec = spec("child", unbound = { _, id ->
            if (id == releasing.id) keeper.unbind(keeper.vm)
        })
        keeper.vm.child(childSpec)
        val generation = releasing.watched
        events.clear()

        releasing.unbind(generation)

        assertTrue(generation.isDisposed)
        assertNull(ViewModel.maybeReadCached<CommitNode>(key = "parent"))
        assertEquals(1, events.count { it == "unbind:parent:${releasing.id}" })
        assertEquals(1, events.count { it == "unbind:parent:${keeper.id}" })
        assertEquals(1, events.count { it == "dispose:parent" })
        assertEquals(1, events.count { it == "cleanup:parent" })
        assertTrue(events.indexOf("unbind:parent:${releasing.id}") < events.indexOf("dispose:parent"))
    }

    @Test
    fun pendingParentOnUnbindCanInvalidateALaterReleaseInTheSameSnapshot() {
        lateinit var first: CommitOwner
        lateinit var second: CommitOwner
        lateinit var last: CommitOwner
        val parentSpec = spec("parent", unbound = { _, id ->
            if (id == first.id) second.watched
        })
        first = owner(parentSpec)
        second = owner(parentSpec)
        last = owner(parentSpec)
        var cascade = true
        val childSpec = spec("child", unbound = { _, id ->
            if (cascade) when (id) {
                first.id -> second.unbind(second.vm)
                second.id -> {
                    cascade = false
                    last.unbind(last.vm)
                }
            }
        })
        first.vm.child(childSpec)
        second.watched
        last.vm
        val generation = first.vm
        events.clear()

        first.unbind(generation)

        assertFalse(generation.isDisposed)
        assertTrue(generation === second.vm)
        assertEquals(listOf(second), generation.refHandler.owners)
        assertEquals(listOf(second.id), handle("parent").bindingIds)
        assertEquals(1, events.count { it == "unbind:parent:${first.id}" })
        assertEquals(1, events.count { it == "unbind:parent:${last.id}" })
        assertEquals(0, events.count { it == "unbind:parent:${second.id}" })
        generation.notifyListeners()
        assertEquals(1, second.updates)
        second.dispose()
        assertTrue(generation.isDisposed)
        assertEquals(1, events.count { it == "unbind:parent:${second.id}" })
        assertEquals(1, events.count { it == "dispose:parent" })
    }

    @Test
    fun onBindCanWatchTheSameGenerationReentrantly() {
        lateinit var owner: CommitOwner
        var sameGeneration = false
        owner = owner(spec("node", bound = { vm, _ -> sameGeneration = owner.watched === vm }))
        owner.vm.notifyListeners()
        assertTrue(sameGeneration)
        assertEquals(1, owner.updates)
        assertEquals(listOf("create:node", "bind:node:${owner.id}"), events)
        assertEquals(listOf(owner), owner.vm.refHandler.owners)
    }

    @Test
    fun ownerDisposalDuringCreationRollsBackEvenRetainedGenerations() {
        for (phase in listOf("builder", "create", "bind")) {
            for (retained in listOf(false, true)) {
                lateinit var owner: CommitOwner
                val key = "$phase-$retained"
                owner = owner(spec(key, retained = retained,
                    construct = { if (phase == "builder") owner.dispose() },
                    created = { if (phase == "create") owner.dispose() },
                    bound = { _, _ -> if (phase == "bind") owner.dispose() }))
                assertThrows(ViewModelError::class.java) { owner.vm }
                assertTrue(owner.isDisposed)
                assertEquals(1, events.count { it == "dispose:$key" })
                assertEquals(1, events.count { it == "cleanup:$key" })
                assertNull(ViewModel.maybeReadCached<CommitNode>(key = key))
            }
        }
    }

    @Test
    fun joiningOwnerDisposalPreservesExistingOwnersAndWatch() {
        lateinit var joining: CommitOwner
        val spec = spec("node", bound = { _, id -> if (id == joining.id) joining.dispose() })
        val keeper = owner(spec)
        joining = owner(spec)
        keeper.watched
        assertThrows(ViewModelError::class.java) { joining.watched }
        assertEquals(listOf(keeper), keeper.vm.refHandler.owners)
        assertEquals(listOf(keeper.id), handle("node").bindingIds)
        keeper.vm.notifyListeners()
        assertEquals(1, keeper.updates)
        assertEquals(0, joining.updates)
        assertFalse(events.contains("dispose:node"))
    }

    @Test
    fun tentativeGenerationAcquiredByAnotherOwnerSurvivesFailedAttachment() {
        lateinit var joining: CommitOwner
        lateinit var keeper: CommitOwner
        val spec = spec("node", bound = { _, id ->
            if (id == joining.id) {
                keeper.watched
                joining.dispose()
            }
        })
        joining = owner(spec)
        keeper = owner(spec)
        assertThrows(ViewModelError::class.java) { joining.vm }
        assertEquals(listOf(keeper), keeper.vm.refHandler.owners)
        assertEquals(listOf(keeper.id), handle("node").bindingIds)
        keeper.vm.notifyListeners()
        assertEquals(1, keeper.updates)
        assertFalse(events.contains("dispose:node"))
    }

    @Test
    fun onUnbindReacquiresWithoutLosingReplacementRegistration() {
        lateinit var owner: CommitOwner
        var rebind = true
        val spec = spec("node", unbound = { _, id ->
            if (id == owner.id && rebind) {
                rebind = false
                owner.watched
            }
        })
        val keeper = owner(spec)
        owner = owner(spec)
        keeper.vm
        owner.watched
        events.clear()
        owner.unbind(owner.vm)
        assertEquals(listOf("unbind:node:${owner.id}", "bind:node:${owner.id}"), events)
        assertEquals(listOf(keeper, owner), owner.vm.refHandler.owners)
        owner.vm.notifyListeners()
        assertEquals(1, owner.updates)
        owner.dispose()
        assertEquals(listOf(keeper), keeper.vm.refHandler.owners)
    }

    @Test
    fun childOnBindCanRemoveJoiningRootDuringPropagation() {
        lateinit var joining: CommitOwner
        val parentSpec = spec("parent")
        val childSpec = spec("child", bound = { _, id -> if (id == joining.id) joining.dispose() })
        val siblingSpec = spec("sibling")
        val keeper = owner(parentSpec)
        joining = owner(parentSpec)
        keeper.vm.child(childSpec)
        keeper.vm.child(siblingSpec)
        assertThrows(ViewModelError::class.java) { joining.vm }
        assertEquals(listOf(keeper), keeper.vm.refHandler.externalOwners)
        for (spec in listOf(childSpec, siblingSpec)) {
            assertEquals(listOf(keeper), keeper.vm.child(spec).refHandler.externalOwners)
        }
        assertFalse(handle("child").bindingIds.contains(joining.id))
        assertFalse(handle("sibling").bindingIds.contains(joining.id))
        keeper.dispose()
        assertEquals(3, events.count { it.startsWith("dispose:") })
    }

    @Test
    fun ownerListenersDoNotDeliverObsoleteSnapshotsAfterReentry() {
        val spec = spec("node")
        val keeper = owner(spec)
        val joining = owner(spec)
        val snapshots = mutableListOf<List<String>>()
        keeper.vm.refHandler.addOwnerChangeListener { ids, _, _ ->
            if (joining.id in ids) joining.dispose()
        }
        keeper.vm.refHandler.addOwnerChangeListener { ids, _, _ -> snapshots += ids }
        assertThrows(ViewModelError::class.java) { joining.vm }
        assertEquals(listOf(listOf(keeper.id)), snapshots)
        assertEquals(listOf(keeper), keeper.vm.refHandler.owners)
    }

    @Test
    fun cycleRejectionHasNoLifecycleOrOwnerSideEffects() {
        val a = owner(spec("a"))
        val b = owner(spec("b"))
        a.watched.child(b.spec, watch = true)
        b.watched
        val originalOwners = handle("a").bindingIds
        var changes = 0
        a.vm.refHandler.addOwnerChangeListener { _, _, _ -> changes++ }
        events.clear()
        assertThrows(ViewModelError::class.java) { b.vm.child(a.spec) }
        assertTrue(events.isEmpty())
        assertEquals(0, changes)
        assertEquals(originalOwners, handle("a").bindingIds)
        b.vm.notifyListeners()
        assertEquals(1, a.updates)
        assertEquals(1, b.updates)
    }

    @Test
    fun onBindSeesCommittedEdgeWhenRejectingReverseCycle() {
        lateinit var a: CommitOwner
        var rejected: Throwable? = null
        val bSpec = spec("b", bound = { vm, id ->
            if (id != a.id) rejected = runCatching { vm.child(a.spec) }.exceptionOrNull()
        })
        a = owner(spec("a"))
        a.vm.child(bSpec)
        assertTrue(rejected is ViewModelError)
        assertEquals(listOf(a), a.vm.refHandler.owners)
        assertEquals(listOf(a), a.vm.child(bSpec).refHandler.externalOwners)
    }

    @Test
    fun rejectedTagBatchPreservesExistingChildEdgesAndSubscriptions() {
        for (watch in listOf(false, true)) {
            val tag = "batch-$watch"
            val a = owner(spec("a-$watch", tag = tag))
            val b = owner(spec("b-$watch", tag = tag))
            val c = owner(spec("c-$watch", tag = tag))
            a.watched
            b.vm
            c.vm
            a.vm.child(c.spec, watch = true)
            val bOwners = handle("b-$watch").bindingIds
            val cOwners = handle("c-$watch").bindingIds
            events.clear()
            // Newest-first order places the invalid self edge last.
            assertThrows(ViewModelError::class.java) { a.vm.batch(tag, watch) }
            assertTrue(events.isEmpty())
            assertEquals(bOwners, handle("b-$watch").bindingIds)
            assertEquals(cOwners, handle("c-$watch").bindingIds)
            c.vm.notifyListeners()
            b.vm.notifyListeners()
            assertEquals(1, a.updates)
        }
    }

    @Test
    fun keyAttachmentRejectionDoesNotFallBackToAnotherTaggedInstance() {
        val a = owner(spec("a", tag = "batch"))
        val b = owner(spec("b", tag = "batch"))
        a.vm
        b.vm
        events.clear()
        assertThrows(ViewModelError::class.java) {
            a.vm.viewModelBinding.readCached<CommitNode>(key = "a", tag = "batch")
        }
        assertTrue(events.isEmpty())
        assertEquals(listOf(b.id), handle("b").bindingIds)
    }

    @Test
    fun failedConstructionReleasesTentativeEdgesAndKeepsSharedWatch() {
        val child = owner(spec("child"))
        val parent = owner(spec("failed", construct = { vm ->
            vm.child(child.spec, watch = true)
            error("constructor failed")
        }))
        child.watched
        assertThrows(IllegalStateException::class.java) { parent.vm }
        assertEquals(listOf(child), child.vm.refHandler.owners)
        assertEquals(listOf(child.id), handle("child").bindingIds)
        assertEquals(1, events.count { it == "cleanup:failed" })
        assertNull(ViewModel.maybeReadCached<CommitNode>(key = "failed"))
        child.vm.notifyListeners()
        assertEquals(1, child.updates)
        assertEquals(0, parent.updates)
    }

    @Test
    fun batchInterruptedByRecycleRollsBackOnlyNewRegistrations() {
        lateinit var joining: CommitOwner
        val b = owner(spec("b", tag = "batch"))
        val a = owner(spec("a", tag = "batch", bound = { _, id ->
            if (id == joining.id) b.recycle(b.vm)
        }))
        joining = owner(spec("existing"))
        b.vm
        a.watched
        joining.watched
        assertThrows(ViewModelError::class.java) { joining.readCachesByTag<CommitNode>("batch") }
        assertEquals(listOf(a), a.vm.refHandler.owners)
        assertEquals(listOf(joining), joining.vm.refHandler.owners)
        a.vm.notifyListeners()
        joining.vm.notifyListeners()
        assertEquals(1, a.updates)
        assertEquals(1, joining.updates)
    }

    @Test
    fun failedBatchPreservesRegistrationReplacedByReentrantWatch() {
        lateinit var joining: CommitOwner
        lateinit var aSpec: ViewModelSpec<CommitNode>
        val b = owner(spec("b", tag = "batch"))
        var replace = true
        aSpec = spec("a", tag = "batch", bound = { vm, id ->
            if (id == joining.id && replace) {
                replace = false
                joining.unbind(vm)
                joining.watch(aSpec)
                b.recycle(b.vm)
            }
        })
        val a = owner(aSpec)
        joining = owner(spec("existing"))
        b.vm
        a.vm
        joining.watched
        assertThrows(ViewModelError::class.java) { joining.readCachesByTag<CommitNode>("batch") }
        assertEquals(listOf(a, joining), a.vm.refHandler.owners)
        assertEquals(listOf(a.id, joining.id), handle("a").bindingIds)
        a.vm.notifyListeners()
        assertEquals(1, joining.updates)
        joining.dispose()
        assertEquals(listOf(a), a.vm.refHandler.owners)
    }

    @Test
    fun recycleInOnUnbindIsIdempotentAndRefreshGetsNewGeneration() {
        lateinit var owner: CommitOwner
        owner = owner(spec("node", unbound = { vm, _ -> owner.recycle(vm) }))
        val previous = owner.vm
        owner.refreshed = { owner.vm }
        owner.recycle(owner.vm)
        assertNotSame(previous, owner.vm)
        assertFalse(owner.vm.isDisposed)
        assertEquals(listOf(owner), owner.vm.refHandler.owners)
        assertEquals(1, events.count { it == "dispose:node" })
        assertEquals(1, events.count { it.startsWith("unbind:") })
        owner.refreshed = null
    }

    @Test
    fun reentrantChildAttachmentSurvivesOldEdgeCleanup() {
        lateinit var parent: CommitOwner
        var dependencyId: String? = null
        lateinit var childSpec: ViewModelSpec<CommitNode>
        var reattach = true
        val child = owner(spec("child", unbound = { _, id ->
            if (id == dependencyId && reattach) {
                reattach = false
                parent.vm.child(childSpec, watch = true)
            }
        }))
        childSpec = child.spec
        parent = owner(spec("parent"))
        child.vm
        parent.vm.child(child.spec, watch = true)
        dependencyId = child.vm.refHandler.owners.single { it.isDependencyBinding }.id
        events.clear()
        parent.vm.releaseChild(child.spec)
        assertEquals(listOf(child, parent), child.vm.refHandler.externalOwners)
        assertTrue(handle("child").bindingIds.contains(dependencyId))
        assertFalse(events.contains("unbind:child:${parent.id}"))
        parent.dispose()
        assertEquals(listOf(child), child.vm.refHandler.owners)
    }

    @Test
    fun recycleRemovesOldCacheBeforePropagatedUnbindAndCoalescesRefresh() {
        lateinit var parent: CommitOwner
        var resolveInUnbind = true
        val childSpec = spec("child", unbound = { _, id ->
            if (id == parent.id && resolveInUnbind) {
                resolveInUnbind = false
                parent.watched.notifyListeners()
            }
        })
        parent = owner(spec("parent"))
        val previous = parent.watched
        parent.vm.child(childSpec)
        parent.recycle(parent.vm)
        assertNotSame(previous, parent.vm)
        assertFalse(parent.vm.isDisposed)
        assertEquals(1, parent.updates)
        assertEquals(1, events.count { it == "dispose:parent" })
        assertEquals(1, events.count { it == "dispose:child" })
    }

    @Test
    fun bindingDisposalToleratesRecyclingAnotherTrackedHandle() {
        val bSpec = spec("b")
        val owner = owner(spec("a", unbound = { _, _ ->
            ViewModel.maybeReadCached<CommitNode>(key = "b")?.let { InstanceManager.recycle(it) }
        }))
        owner.vm
        owner.read(bSpec)
        owner.dispose()
        assertEquals(2, events.count { it.startsWith("dispose:") })
        assertNull(ViewModel.maybeReadCached<CommitNode>(key = "a"))
        assertNull(ViewModel.maybeReadCached<CommitNode>(key = "b"))
    }
}

private class CommitOwner(val spec: ViewModelSpec<CommitNode>) : ViewModelBinding() {
    val vm by readViewModel(spec)
    val watched by watchViewModel(spec)
    var updates = 0
    var refreshed: (() -> Unit)? = null

    override fun onUpdate() {
        super.onUpdate()
        updates++
        refreshed?.invoke()
    }
}

private class CommitNode(
    private val name: String,
    private val events: MutableList<String>,
    construct: ((CommitNode) -> Unit)?,
    private val created: ((CommitNode) -> Unit)?,
    private val bound: ((CommitNode, String) -> Unit)?,
    private val unbound: ((CommitNode, String) -> Unit)?,
) : ViewModel() {
    init {
        addDispose { events += "cleanup:$name" }
        construct?.invoke(this)
    }

    fun child(spec: ViewModelSpec<CommitNode>, watch: Boolean = false): CommitNode {
        val child by if (watch) viewModelBinding.watchViewModel(spec) else viewModelBinding.readViewModel(spec)
        return child
    }

    fun releaseChild(spec: ViewModelSpec<CommitNode>) = viewModelBinding.unbind(child(spec))

    fun batch(tag: Any, watch: Boolean): List<CommitNode> =
        if (watch) viewModelBinding.watchCachesByTag(tag) else viewModelBinding.readCachesByTag(tag)

    override fun onCreate(arg: InstanceArg) {
        super.onCreate(arg)
        events += "create:$name"
        created?.invoke(this)
    }

    override fun onBind(arg: InstanceArg, bindingId: String) {
        super.onBind(arg, bindingId)
        events += "bind:$name:$bindingId"
        bound?.invoke(this, bindingId)
    }

    override fun onUnbind(arg: InstanceArg, bindingId: String) {
        super.onUnbind(arg, bindingId)
        events += "unbind:$name:$bindingId"
        unbound?.invoke(this, bindingId)
    }

    override fun onDispose(arg: InstanceArg) {
        events += "dispose:$name"
        super.onDispose(arg)
    }
}
