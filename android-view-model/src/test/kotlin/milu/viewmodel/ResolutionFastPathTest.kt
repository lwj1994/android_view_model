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

class ResolutionFastPathTest {
    @Before
    fun setUp() = ViewModel.reset()

    @After
    fun tearDown() = ViewModel.reset()

    @Test
    fun repeatedResolution_skipsBuildAndTagButRevalidatesIdentityAndRetention() {
        var updates = 0
        val binding = ViewModelBinding { updates += 1 }
        val factory = CountingResolutionFactory(viewModelSpec { FastPathModel() })
        try {
            val first = binding.read(factory)
            first.notifyListeners()
            assertEquals(0, updates)

            repeat(5) {
                assertSame(first, binding.read(factory))
                assertSame(first, binding.watch(factory))
                assertSame(first, binding.watch(factory))
            }
            assertEquals(1, factory.buildCalls)
            assertEquals(1, factory.tagCalls)
            assertEquals(16, factory.keyCalls)
            assertEquals(16, factory.retentionCalls)

            first.notifyListeners()
            assertEquals(1, updates)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun differentSpecsAndArguments_shareTheBindingPrivateIdentity() {
        val binding = ViewModelBinding()
        val otherBinding = ViewModelBinding()
        val builds = mutableListOf<String>()
        val firstSpec = viewModelSpec {
            builds += "first"
            FastPathModel("first")
        }
        val secondSpec = viewModelSpec {
            builds += "second"
            FastPathModel("second")
        }
        val argumentSpec = viewModelSpecWithArg<FastPathModel, String>(
            builder = { label ->
                builds += label
                FastPathModel(label)
            },
        )
        try {
            val first = binding.read(firstSpec)
            assertSame(first, binding.read(secondSpec))
            assertSame(first, binding.watch(argumentSpec("third")))
            assertSame(first, binding.read(argumentSpec("fourth")))
            assertEquals("first", first.label)
            assertEquals(listOf("first"), builds)

            val other = otherBinding.read(argumentSpec("other-binding"))
            assertNotSame(first, other)
            assertEquals("other-binding", other.label)
            assertEquals(listOf("first", "other-binding"), builds)
        } finally {
            otherBinding.dispose()
            binding.dispose()
        }
    }

    @Test
    fun readAfterUnbind_restoresDirectOwnershipOfSharedHandle() {
        val owner = ViewModelBinding()
        val other = ViewModelBinding()
        val spec = viewModelSpec(key = "shared") { FastPathModel() }
        try {
            val first = owner.read(spec)
            assertSame(first, other.read(spec))

            owner.unbind(first)
            assertFalse(first.refHandler.hasDirectRef(owner))
            assertFalse(first.isDisposed)
            assertSame(first, owner.read(spec))
            assertTrue(first.refHandler.hasDirectRef(owner))

            other.dispose()
            assertFalse(first.isDisposed)
            owner.dispose()
            assertTrue(first.isDisposed)
        } finally {
            other.dispose()
            owner.dispose()
        }
    }

    @Test
    fun readAfterDirectUnbind_restoresOwnershipAlongsideParentPropagation() {
        val owner = ViewModelBinding()
        val childSpec = viewModelSpec(key = "shared-child") { FastPathModel() }
        val parentSpec = viewModelSpec { FastPathParent(childSpec) }
        try {
            val child = owner.read(childSpec)
            val parent = owner.read(parentSpec)
            assertSame(child, parent.child)

            owner.unbind(child)
            assertFalse(child.refHandler.hasDirectRef(owner))
            assertTrue(child.refHandler.owners.any { it === owner })
            assertFalse(child.isDisposed)
            assertSame(child, owner.read(childSpec))
            assertTrue(child.refHandler.hasDirectRef(owner))

            owner.recycle(parent)
            assertTrue(parent.isDisposed)
            assertFalse(child.isDisposed)
            assertSame(child, owner.read(childSpec))
            owner.dispose()
            assertTrue(child.isDisposed)
        } finally {
            owner.dispose()
        }
    }

    @Test
    fun unbindLastOwner_recyclesAndRemovesWatchAndExplicitListeners() {
        var updates = 0
        var listenerCalls = 0
        val binding = ViewModelBinding { updates += 1 }
        val spec = viewModelSpec { FastPathModel() }
        try {
            val first = binding.watch(spec)
            binding.listen(spec) { listenerCalls += 1 }
            first.notifyListeners()
            assertEquals(1, updates)
            assertEquals(1, listenerCalls)

            binding.unbind(first)
            assertTrue(first.isDisposed)
            assertFalse(first.hasListeners)
            first.notifyListeners()
            assertEquals(1, updates)
            assertEquals(1, listenerCalls)

            val next = binding.watch(spec)
            assertNotSame(first, next)
            next.notifyListeners()
            assertEquals(2, updates)
            assertEquals(1, listenerCalls)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun unbindRetainedInstance_stopsSubscriptionsUntilWatchResolvesAgain() {
        var updates = 0
        var listenerCalls = 0
        val binding = ViewModelBinding { updates += 1 }
        val spec = viewModelSpec(key = "retained", aliveForever = true) { FastPathModel() }
        try {
            val first = binding.watch(spec)
            binding.listen(spec) { listenerCalls += 1 }
            first.notifyListeners()
            assertEquals(1, updates)
            assertEquals(1, listenerCalls)

            binding.unbind(first)
            assertFalse(first.isDisposed)
            assertTrue(first.refHandler.owners.isEmpty())
            assertFalse(first.hasListeners)
            first.notifyListeners()
            assertEquals(1, updates)
            assertEquals(1, listenerCalls)

            assertSame(first, binding.read(spec))
            first.notifyListeners()
            assertEquals(1, updates)
            assertSame(first, binding.watch(spec))
            first.notifyListeners()
            assertEquals(2, updates)
            assertEquals(1, listenerCalls)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun nestedDelegates_resolveNewHandlesAfterChildRecycleAndRuntimeReset() {
        var childBuilds = 0
        var parentBuilds = 0
        val binding = ViewModelBinding()
        val childSpec = viewModelSpec {
            childBuilds += 1
            FastPathModel()
        }
        val parentSpec = viewModelSpec {
            parentBuilds += 1
            FastPathParent(childSpec)
        }
        try {
            val parent by binding.readViewModel(parentSpec)
            val firstParent = parent
            val firstChild = parent.child
            assertSame(firstChild, parent.child)

            binding.recycle(firstChild)
            val secondChild = parent.child
            assertTrue(firstChild.isDisposed)
            assertNotSame(firstChild, secondChild)
            assertSame(firstParent, parent)
            assertSame(secondChild, parent.child)

            ViewModel.reset()
            assertTrue(firstParent.isDisposed)
            assertTrue(secondChild.isDisposed)
            val nextParent = parent
            val nextChild = parent.child
            assertNotSame(firstParent, nextParent)
            assertNotSame(secondChild, nextChild)
            assertSame(nextChild, parent.child)
            assertEquals(2, parentBuilds)
            assertEquals(3, childBuilds)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun reset_rejectsReadAndWatchHitsWhileAnotherCachedStoreIsStillLive() {
        val binding = ViewModelBinding()
        val errors = mutableListOf<Throwable?>()
        var peerWasLive = false
        lateinit var peer: FastPathModel
        val peerSpec = viewModelSpec { FastPathModel() }
        val resetProbeSpec = viewModelSpec {
            FastPathResetProbe {
                peerWasLive = !peer.isDisposed
                errors += runCatching { binding.read(peerSpec) }.exceptionOrNull()
                errors += runCatching { binding.watch(peerSpec) }.exceptionOrNull()
            }
        }
        try {
            // Stores are reset in insertion order: the probe runs before the
            // peer's Store, while the binding still has a live cached handle.
            binding.read(resetProbeSpec)
            peer = binding.read(peerSpec)
            assertSame(peer, binding.watch(peerSpec))

            ViewModel.reset()

            assertTrue(peerWasLive)
            assertEquals(2, errors.size)
            assertTrue(errors.all { it is ViewModelError })
            assertTrue(peer.isDisposed)
            assertEquals(0, InstanceManager.debugStoreCount)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun cachedUnkeyedResolution_cannotBypassDynamicRetentionValidation() {
        val binding = ViewModelBinding()
        val spec = viewModelSpec { FastPathModel("original") }
        val invalidProxy = viewModelSpec(aliveForever = true) { FastPathModel("invalid") }
        val factory = CountingResolutionFactory(spec)
        try {
            val first = binding.read(factory)
            spec.setProxy(invalidProxy)

            repeat(2) {
                assertThrows(ViewModelError::class.java) { binding.read(factory) }
                assertThrows(ViewModelError::class.java) { binding.watch(factory) }
            }
            assertEquals(1, factory.buildCalls)
            assertEquals(1, factory.tagCalls)
            assertEquals(5, factory.keyCalls)
            assertEquals(5, factory.retentionCalls)
            assertFalse(first.isDisposed)

            spec.clearProxy()
            assertSame(first, binding.read(factory))
            assertEquals(1, factory.buildCalls)
        } finally {
            spec.clearProxy()
            binding.dispose()
        }
    }

    @Test
    fun explicitKeyOverride_createsAnotherIdentityAndRestoreReusesOriginal() {
        var builds = 0
        val binding = ViewModelBinding()
        val spec = viewModelSpec(key = "original") {
            builds += 1
            FastPathModel("original")
        }
        val override = viewModelSpec(key = "override") {
            builds += 1
            FastPathModel("override")
        }
        var restore: () -> Unit = {}
        try {
            val model by binding.readViewModel(spec)
            val original = model
            restore = spec.overrideWith(override)
            val replacement = model
            assertNotSame(original, replacement)
            assertEquals("override", replacement.label)
            assertSame(replacement, model)

            restore()
            assertSame(original, model)
            assertFalse(replacement.isDisposed)
            assertEquals(2, builds)
        } finally {
            restore()
            binding.dispose()
        }
    }

    @Test
    fun disposedBinding_rejectsReadAndWatchDespiteLiveSharedInstance() {
        val binding = ViewModelBinding()
        val keeper = ViewModelBinding()
        val factory = CountingResolutionFactory(
            viewModelSpec(key = "shared") { FastPathModel() },
        )
        try {
            val first = binding.watch(factory)
            assertSame(first, keeper.read(factory))
            binding.dispose()
            assertFalse(first.isDisposed)

            assertThrows(ViewModelError::class.java) { binding.read(factory) }
            assertThrows(ViewModelError::class.java) { binding.watch(factory) }
            assertEquals(1, factory.buildCalls)
            assertSame(first, keeper.read(factory))
        } finally {
            keeper.dispose()
            binding.dispose()
        }
    }

    @Test
    fun recyclingHandle_isNotReturnedBeforeItsDisposalListenerDetachesTheCache() {
        val spec = viewModelSpec(key = "recycling-shared") { FastPathModel() }
        val laterBinding = ViewModelBinding()
        var duringDisposal: FastPathModel? = null
        val earlierBinding = ViewModelBinding {
            duringDisposal = laterBinding.read(spec)
        }
        var attemptedDuringRecycle = false
        var rejectedDuringRecycle: Throwable? = null
        val removeLifecycle = ViewModel.addLifecycle(object : ViewModelLifecycle {
            override fun onUnbind(viewModel: ViewModel, arg: InstanceArg, bindingId: String) {
                if (bindingId == earlierBinding.id && !attemptedDuringRecycle) {
                    // Recycle marks the generation before hooks; it cannot regain owners
                    // while it is still cached, before the disposal listeners begin.
                    attemptedDuringRecycle = true
                    rejectedDuringRecycle = runCatching { laterBinding.read(spec) }.exceptionOrNull()
                }
            }
        })
        try {
            val original = earlierBinding.read(spec)
            assertSame(original, laterBinding.read(spec))

            earlierBinding.recycle(original)

            val replacement = requireNotNull(duringDisposal)
            assertTrue(attemptedDuringRecycle)
            assertTrue(rejectedDuringRecycle is ViewModelError)
            assertTrue(original.isDisposed)
            assertNotSame(original, replacement)
            assertFalse(replacement.isDisposed)
            assertSame(replacement, laterBinding.read(spec))
        } finally {
            removeLifecycle()
            earlierBinding.dispose()
            laterBinding.dispose()
        }
    }

    @Test
    fun dependencyUnbind_releasesAllPropagatedSourcesButKeepsIndependentOwner() {
        val firstRoot = ViewModelBinding()
        val secondRoot = ViewModelBinding()
        val independentOwner = ViewModelBinding()
        val childSpec = viewModelSpec(key = "child") { FastPathModel() }
        val parentSpec = viewModelSpec(key = "parent") { FastPathParent(childSpec) }
        try {
            val parent = firstRoot.read(parentSpec)
            assertSame(parent, secondRoot.read(parentSpec))
            val firstChild = parent.child
            assertTrue(firstChild.refHandler.owners.containsAll(listOf(firstRoot, secondRoot)))

            parent.viewModelBinding.unbind(firstChild)
            assertTrue(firstChild.isDisposed)
            assertTrue(firstChild.refHandler.owners.isEmpty())
            assertFalse(parent.isDisposed)

            val nextChild = independentOwner.read(childSpec)
            assertNotSame(firstChild, nextChild)
            assertSame(nextChild, parent.child)
            parent.viewModelBinding.unbind(nextChild)
            assertFalse(nextChild.isDisposed)
            assertEquals(listOf(independentOwner), nextChild.refHandler.owners)

            firstRoot.dispose()
            secondRoot.dispose()
            assertTrue(parent.isDisposed)
            assertFalse(nextChild.isDisposed)
            assertEquals(listOf(independentOwner), nextChild.refHandler.owners)
            independentOwner.dispose()
            assertTrue(nextChild.isDisposed)
        } finally {
            independentOwner.dispose()
            secondRoot.dispose()
            firstRoot.dispose()
        }
    }
}

private class FastPathModel(val label: String = "") : ViewModel()

private class FastPathParent(childSpec: ViewModelSpec<FastPathModel>) : ViewModel() {
    val child by readViewModel(childSpec) { viewModelBinding }
}

private class FastPathResetProbe(private val onDisposeBlock: () -> Unit) : ViewModel() {
    override fun dispose() = onDisposeBlock()
}

private class CountingResolutionFactory(
    private val spec: ViewModelSpec<FastPathModel>,
) : ViewModelFactory<FastPathModel> {
    override val modelClass
        get() = spec.modelClass

    var buildCalls = 0
        private set
    var keyCalls = 0
        private set
    var tagCalls = 0
        private set
    var retentionCalls = 0
        private set

    override fun build(): FastPathModel {
        buildCalls += 1
        return spec.build()
    }

    override fun key(): Any? {
        keyCalls += 1
        return spec.key()
    }

    override fun tag(): Any? {
        tagCalls += 1
        return spec.tag()
    }

    override fun aliveForever(): Boolean {
        retentionCalls += 1
        return spec.aliveForever()
    }
}
