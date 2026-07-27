package milu.viewmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ParentDependencyBindingTest {
    @Before
    fun setUp() {
        InstanceManager.debugReset()
        ViewModel.debugReset()
        RollbackChild.created = 0
        RollbackChild.disposed = 0
    }

    @After
    fun tearDown() {
        InstanceManager.debugReset()
        ViewModel.debugReset()
    }

    @Test
    fun unkeyedIdentity_isStablePerBindingAndIsolatedAcrossBindings() {
        val first = ViewModelBinding()
        val second = ViewModelBinding()

        val firstValue = first.read(childSpec)
        assertSame(firstValue, first.read(childSpec))
        assertNotSame(firstValue, second.read(childSpec))

        first.dispose()
        second.dispose()
    }

    @Test
    fun sharedParent_propagatesOwnerChangesWithoutSwitchingChildGeneration() {
        val ownerA = ViewModelBinding()
        val parent = ownerA.read(parentSpec)
        val child = parent.child
        val dependencyBindingId = child.boundIds.single { it != ownerA.id }

        val ownerB = ViewModelBinding()
        assertSame(parent, ownerB.read(parentSpec))
        assertTrue(child.refHandler.owners.any { it === ownerB })
        assertTrue(child.boundIds.containsAll(listOf(dependencyBindingId, ownerA.id, ownerB.id)))

        ownerA.dispose()
        assertFalse(parent.isDisposed)
        assertFalse(child.isDisposed)
        assertSame(child, parent.child)
        assertFalse(child.refHandler.owners.any { it === ownerA })
        assertTrue(child.refHandler.owners.any { it === ownerB })

        ownerB.dispose()
        assertTrue(parent.isDisposed)
        assertTrue(child.isDisposed)
        assertEquals(setOf(dependencyBindingId, ownerA.id, ownerB.id), child.unboundIds.toSet())
    }

    @Test
    fun directAndParentOwnershipFromOneRoot_areReleasedIndependently() {
        val owner = ViewModelBinding()
        val directChild = owner.read(sharedChildSpec)
        val parent = owner.read(parentSpec)
        assertSame(directChild, parent.sharedChild)

        owner.recycle(parent)

        assertTrue(parent.isDisposed)
        assertFalse(directChild.isDisposed)
        assertFalse(directChild.unboundIds.contains(owner.id))
        assertSame(directChild, owner.read(sharedChildSpec))

        owner.dispose()
        assertTrue(directChild.isDisposed)
    }

    @Test
    fun rootCanGloballyRecycleChildOwnedOnlyThroughParent() {
        val owner = CountingBinding()
        val parent = owner.watch(parentSpec)
        val child = parent.child
        owner.updates = 0

        owner.recycle(child)

        assertTrue(child.isDisposed)
        assertEquals(1, parent.dependencyNotifications)
        assertEquals(1, owner.updates)
        assertNotSame(child, parent.child)
        owner.dispose()
    }

    @Test
    fun readDoesNotBubbleAndWatchBubblesOnce() {
        val owner = CountingBinding()
        val parent = owner.watch(parentSpec)
        val child = parent.child
        owner.updates = 0

        child.emit()
        assertEquals(0, parent.dependencyNotifications)
        assertEquals(0, owner.updates)

        assertSame(child, parent.watchedChild)
        child.emit()
        assertEquals(1, parent.dependencyNotifications)
        assertEquals(1, owner.updates)
        owner.dispose()
    }

    @Test
    fun diamondPropagation_updatesEachBindingOncePerTransaction() {
        val owner = CountingBinding()
        val root = owner.watch(diamondRootSpec)
        val left = root.left
        val right = root.right
        val leaf = left.leaf
        assertSame(leaf, right.leaf)
        assertSame(leaf, owner.watch(diamondLeafSpec))
        owner.updates = 0

        leaf.emit()

        assertEquals(1, left.dependencyNotifications)
        assertEquals(1, right.dependencyNotifications)
        assertEquals(1, root.dependencyNotifications)
        assertEquals(1, owner.updates)
        owner.dispose()
    }

    @Test
    fun recreatingParent_startsANewPrivateChildGeneration() {
        val owner = CountingBinding()
        val parent = owner.watch(parentSpec)
        val child = parent.child
        owner.updates = 0

        val recreated = owner.recreate(parent)

        assertTrue(parent.isDisposed)
        assertTrue(child.isDisposed)
        assertThrows(ViewModelError::class.java) { parent.child }
        assertNotSame(parent, recreated)
        assertNotSame(child, recreated.child)
        assertEquals(1, owner.updates)
        owner.dispose()
    }

    @Test
    fun recreatingChild_movesParentListenSubscription() {
        val owner = CountingBinding()
        val parent = owner.watch(parentSpec)
        val child = parent.child
        parent.listenToChild()

        val recreated = owner.recreate(child)
        recreated.emit()

        assertSame(recreated, parent.child)
        assertEquals(1, parent.listenCallbacks)
        owner.dispose()
    }

    @Test
    fun aliveForever_requiresExplicitKeyAtRootAndNestedResolutions() {
        val root = ViewModelBinding()
        val rootError = assertThrows(ViewModelError::class.java) {
            root.read(aliveUnkeyedChildSpec)
        }
        assertTrue(rootError.message!!.contains("must use an explicit key"))

        val argSpec = viewModelSpecWithArg<ChildViewModel, Int>(
            builder = { ChildViewModel() },
            key = { null },
            aliveForever = { true },
        )
        val argError = assertThrows(ViewModelError::class.java) {
            root.read(argSpec(1))
        }
        assertEquals(rootError.message, argError.message)
        root.dispose()

        val owner = ViewModelBinding()
        val parent = owner.read(parentSpec)

        val nestedError = assertThrows(ViewModelError::class.java) {
            parent.aliveUnkeyedChild
        }
        assertEquals(rootError.message, nestedError.message)
        val child = parent.aliveKeyedChild
        owner.dispose()

        assertTrue(parent.isDisposed)
        assertFalse(child.isDisposed)
        val next = ViewModelBinding()
        assertSame(child, next.read(aliveKeyedChildSpec))
        next.recycle(child)
        assertTrue(child.isDisposed)
        next.dispose()
    }

    @Test
    fun aliveForever_requiresExplicitKeyAtStoreBoundary() {
        var buildCount = 0
        val error = assertThrows(ViewModelError::class.java) {
            InstanceManager.get(
                Any::class,
                InstanceFactory(
                    builder = {
                        buildCount += 1
                        Any()
                    },
                    arg = InstanceArg(aliveForever = true),
                ),
            )
        }

        assertEquals(
            "An aliveForever instance must use an explicit key.",
            error.message,
        )
        assertEquals(0, buildCount)
    }

    @Test
    fun failedBuilder_rollsBackChildrenCreatedByItsDependencyScope() {
        val owner = ViewModelBinding()

        assertThrows(IllegalStateException::class.java) { owner.read(throwingParentSpec) }
        assertEquals(1, RollbackChild.created)
        assertEquals(1, RollbackChild.disposed)
        owner.dispose()
    }

    @Test
    fun constructionCycle_failsWithoutOverflowingTheStack() {
        SelfRecursive.spec = viewModelSpec { SelfRecursive() }
        val owner = ViewModelBinding()

        val error = assertThrows(ViewModelError::class.java) { owner.read(SelfRecursive.spec) }
        assertTrue(error.message.orEmpty().contains("Circular ViewModel construction"))
        owner.dispose()
    }

    @Test
    fun runtimeOwnershipCycle_isRejectedAtomically() {
        RuntimeA.spec = viewModelSpec(key = "runtime-a") { RuntimeA() }
        RuntimeB.spec = viewModelSpec(key = "runtime-b") { RuntimeB() }
        val owner = ViewModelBinding()
        val a = owner.read(RuntimeA.spec)
        val b = owner.read(RuntimeB.spec)

        assertSame(b, a.dependency)
        val error = assertThrows(ViewModelError::class.java) { b.dependency }
        assertTrue(error.message.orEmpty().contains("Circular ViewModel dependency"))
        assertFalse(a.isDisposed)
        assertFalse(b.isDisposed)
        owner.dispose()
        assertTrue(a.isDisposed)
        assertTrue(b.isDisposed)
    }

    @Test
    fun resetInsideRecreate_disposesDetachedReplacement() {
        val owner = ViewModelBinding()
        val original = owner.read(sharedChildSpec)
        var replacement: ChildViewModel? = null

        assertThrows(ViewModelError::class.java) {
            owner.recreate(original) {
                InstanceManager.debugReset()
                ChildViewModel().also { replacement = it }
            }
        }

        assertTrue(original.isDisposed)
        assertTrue(replacement!!.isDisposed)
        assertEquals(0, InstanceManager.debugStoreCount)
        owner.dispose()
    }
}

private open class ChildViewModel : ViewModel() {
    val boundIds = mutableListOf<String>()
    val unboundIds = mutableListOf<String>()

    fun emit() = notifyListeners()

    override fun onBind(arg: InstanceArg, bindingId: String) {
        super.onBind(arg, bindingId)
        boundIds += bindingId
    }

    override fun onUnbind(arg: InstanceArg, bindingId: String) {
        super.onUnbind(arg, bindingId)
        unboundIds += bindingId
    }
}

private val childSpec = viewModelSpec { ChildViewModel() }
private val sharedChildSpec = viewModelSpec(key = "parent-shared-child") { ChildViewModel() }
private val aliveUnkeyedChildSpec = viewModelSpec(aliveForever = true) { ChildViewModel() }
private val aliveKeyedChildSpec = viewModelSpec(
    key = "parent-alive-child",
    aliveForever = true,
) { ChildViewModel() }

private class ParentViewModel : ViewModel() {
    var dependencyNotifications = 0
    var listenCallbacks = 0

    val child: ChildViewModel
        get() = viewModelBinding.read(childSpec)
    val watchedChild: ChildViewModel
        get() = viewModelBinding.watch(childSpec)
    val sharedChild: ChildViewModel
        get() = viewModelBinding.read(sharedChildSpec)
    val aliveUnkeyedChild: ChildViewModel
        get() = viewModelBinding.read(aliveUnkeyedChildSpec)
    val aliveKeyedChild: ChildViewModel
        get() = viewModelBinding.read(aliveKeyedChildSpec)

    fun listenToChild() {
        viewModelBinding.listen(childSpec) { listenCallbacks += 1 }
    }

    override fun onDependencyNotify(viewModel: ViewModel) {
        dependencyNotifications += 1
    }
}

private val parentSpec = viewModelSpec(key = "parent-shared-parent") { ParentViewModel() }

private class CountingBinding : ViewModelBinding() {
    var updates = 0
    override fun onUpdate() {
        super.onUpdate()
        updates += 1
    }
}

private val diamondLeafSpec = viewModelSpec(key = "diamond-leaf") { ChildViewModel() }

private class DiamondBranch : ViewModel() {
    var dependencyNotifications = 0
    val leaf: ChildViewModel
        get() = viewModelBinding.watch(diamondLeafSpec)

    override fun onDependencyNotify(viewModel: ViewModel) {
        dependencyNotifications += 1
    }
}

private val leftBranchSpec = viewModelSpec(key = "diamond-left") { DiamondBranch() }
private val rightBranchSpec = viewModelSpec(key = "diamond-right") { DiamondBranch() }

private class DiamondRoot : ViewModel() {
    var dependencyNotifications = 0
    val left: DiamondBranch
        get() = viewModelBinding.watch(leftBranchSpec)
    val right: DiamondBranch
        get() = viewModelBinding.watch(rightBranchSpec)

    override fun onDependencyNotify(viewModel: ViewModel) {
        dependencyNotifications += 1
    }
}

private val diamondRootSpec = viewModelSpec(key = "diamond-root") { DiamondRoot() }

private class RollbackChild : ViewModel() {
    companion object {
        var created = 0
        var disposed = 0
    }

    init {
        created += 1
    }

    override fun dispose() {
        disposed += 1
    }
}

private val rollbackChildSpec = viewModelSpec { RollbackChild() }

private class ThrowingParent : ViewModel() {
    init {
        viewModelBinding.read(rollbackChildSpec)
        error("parent construction failed")
    }
}

private val throwingParentSpec = viewModelSpec(key = "throwing-parent") { ThrowingParent() }

private class SelfRecursive : ViewModel() {
    companion object {
        lateinit var spec: ViewModelSpec<SelfRecursive>
    }

    init {
        viewModelBinding.read(spec)
    }
}

private class RuntimeA : ViewModel() {
    companion object {
        lateinit var spec: ViewModelSpec<RuntimeA>
    }

    val dependency: RuntimeB
        get() = viewModelBinding.read(RuntimeB.spec)
}

private class RuntimeB : ViewModel() {
    companion object {
        lateinit var spec: ViewModelSpec<RuntimeB>
    }

    val dependency: RuntimeA
        get() = viewModelBinding.read(RuntimeA.spec)
}
