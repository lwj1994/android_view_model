package milu.viewmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnbindLifecycleRegressionTest {
    @Before
    fun setUp() {
        ViewModel.reset()
    }

    @After
    fun tearDown() {
        ViewModel.reset()
    }

    @Test
    fun dependencyUnbind_notifiesParentAfterCleanupSoSynchronousReadGetsANewGeneration() {
        val owner = ViewModelBinding()
        try {
            val childSpec = viewModelSpec { UnbindTestChild() }
            val parentSpec = viewModelSpec { UnbindTestParent(childSpec) }
            val parent by owner.watchViewModel(parentSpec)
            val original = parent.child
            var replacement: UnbindTestChild? = null
            var originalWasDisposedBeforeRead = false
            owner.addUpdateListener {
                originalWasDisposedBeforeRead = original.isDisposed
                replacement = parent.child
            }

            parent.viewModelBinding.unbind(original)

            val resolved = requireNotNull(replacement)
            assertTrue(originalWasDisposedBeforeRead)
            assertNotSame(original, resolved)
            assertFalse(resolved.isDisposed)
            assertTrue(resolved.refHandler.owners.any { it === owner })
            assertTrue(resolved.refHandler.owners.any { it === parent.viewModelBinding })
            assertEquals(1, parent.notifications)
            owner.dispose()
            assertTrue(resolved.isDisposed)
        } finally {
            owner.dispose()
        }
    }

    @Test
    fun dependencyOnUnbind_allowsReacquisitionAndNestedUnbindWithoutLosingTheNewEdge() {
        val owner = ViewModelBinding()
        try {
            var onChildUnbind: ((String) -> Unit)? = null
            val childSpec = viewModelSpec(key = "unbind-guard-child", tag = "unbind-guard") {
                UnbindTestChild(onUnbind = { bindingId -> onChildUnbind?.invoke(bindingId) })
            }
            val parentSpec = viewModelSpec { UnbindTestParent(childSpec) }
            val parent by owner.readViewModel(parentSpec)
            val dependencyBinding = parent.viewModelBinding
            val original = parent.child
            val failures = mutableListOf<Throwable?>()
            val attempts: List<() -> Any?> = listOf(
                { dependencyBinding.read(childSpec) },
                { dependencyBinding.watch(childSpec) },
                { dependencyBinding.readCached<UnbindTestChild>(key = "unbind-guard-child") },
                { dependencyBinding.watchCached<UnbindTestChild>(key = "unbind-guard-child") },
                { dependencyBinding.readCachesByTag<UnbindTestChild>("unbind-guard") },
                { dependencyBinding.watchCachesByTag<UnbindTestChild>("unbind-guard") },
                { dependencyBinding.unbind(original) },
            )
            onChildUnbind = { bindingId ->
                if (bindingId == dependencyBinding.id) {
                    onChildUnbind = null
                    attempts.forEach { attempt ->
                        failures += runCatching(attempt).exceptionOrNull()
                    }
                }
            }

            dependencyBinding.unbind(original)

            assertEquals(attempts.size, failures.size)
            assertTrue(failures.all { it == null })
            assertTrue(original.isDisposed)
            assertEquals(2, parent.notifications)
            onChildUnbind = null
            val replacement = parent.child
            assertNotSame(original, replacement)
            assertFalse(replacement.isDisposed)
            owner.dispose()
            assertTrue(replacement.isDisposed)
        } finally {
            owner.dispose()
        }
    }

    @Test
    fun descendantOnUnbind_canReacquireTheParentThroughItsLiveOwner() {
        val owner = ViewModelBinding()
        try {
            var onChildUnbind: ((String) -> Unit)? = null
            val childSpec = viewModelSpec {
                UnbindTestChild(onUnbind = { bindingId -> onChildUnbind?.invoke(bindingId) })
            }
            val parentSpec = viewModelSpec { UnbindTestParent(childSpec) }
            val parent by owner.watchViewModel(parentSpec)
            val originalParent = parent
            val originalChild = parent.child
            var failure: Throwable? = null
            var attempts = 0
            onChildUnbind = { bindingId ->
                if (bindingId == owner.id) {
                    attempts += 1
                    failure = runCatching { owner.watch(parentSpec) }.exceptionOrNull()
                }
            }

            owner.unbind(originalParent)

            assertEquals(1, attempts)
            assertEquals(null, failure)
            assertFalse(originalParent.isDisposed)
            assertFalse(originalChild.isDisposed)
            onChildUnbind = null
            assertSame(originalParent, parent)
            assertTrue(parent.child.refHandler.owners.any { it === owner })
            owner.dispose()
            assertTrue(originalParent.isDisposed)
            assertTrue(originalChild.isDisposed)
        } finally {
            owner.dispose()
        }
    }

    @Test
    fun onUnbind_allowsAnotherBindingToAcquireTheSurvivingGeneration() {
        val owner = ViewModelBinding()
        val otherOwner = ViewModelBinding()
        try {
            var onChildUnbind: ((String) -> Unit)? = null
            val childSpec = viewModelSpec(key = "unbind-other-owner") {
                UnbindTestChild(onUnbind = { bindingId -> onChildUnbind?.invoke(bindingId) })
            }
            val parentSpec = viewModelSpec { UnbindTestParent(childSpec) }
            val parent by owner.readViewModel(parentSpec)
            val dependencyBinding = parent.viewModelBinding
            val original = parent.child
            var surviving: UnbindTestChild? = null
            onChildUnbind = { bindingId ->
                if (bindingId == dependencyBinding.id) surviving = otherOwner.read(childSpec)
            }

            dependencyBinding.unbind(original)

            assertSame(original, surviving)
            assertFalse(original.isDisposed)
            assertEquals(listOf(otherOwner), original.refHandler.owners)
            onChildUnbind = null
            owner.dispose()
            assertFalse(original.isDisposed)
            otherOwner.dispose()
            assertTrue(original.isDisposed)
        } finally {
            owner.dispose()
            otherOwner.dispose()
        }
    }
}

private class UnbindTestChild(
    private val onUnbind: ((String) -> Unit)? = null,
) : ViewModel() {
    override fun onUnbind(arg: InstanceArg, bindingId: String) {
        super.onUnbind(arg, bindingId)
        onUnbind?.invoke(bindingId)
    }
}

private class UnbindTestParent(
    childSpec: ViewModelSpec<UnbindTestChild>,
) : ViewModel() {
    val child by readViewModel(childSpec) { viewModelBinding }
    var notifications = 0

    override fun notifyListeners() {
        notifications += 1
        super.notifyListeners()
    }
}
