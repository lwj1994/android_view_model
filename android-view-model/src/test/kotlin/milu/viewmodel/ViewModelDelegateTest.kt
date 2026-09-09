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

class ViewModelDelegateTest {
    @Before
    fun setUp() = ViewModel.reset()

    @After
    fun tearDown() = ViewModel.reset()

    @Test
    fun getter_isLazy_reResolvesRecycle_andDeduplicatesSubscriptions() {
        var builds = 0
        var updates = 0
        val spec = viewModelSpec { DelegateModel(++builds) }
        val binding = ViewModelBinding { updates++ }
        try {
            val model by binding.watchViewModel(spec)
            assertEquals(0, builds)
            val first = model
            repeat(10) { assertSame(first, model) }
            assertEquals(1, builds)
            model.notifyListeners()
            assertEquals(1, updates)

            // The callback captures the delegate and resolves on each invocation, not the first instance.
            val callback = { model.generation }
            binding.recycle(first)
            assertEquals(2, callback())
            assertNotSame(first, model)
            assertTrue(first.isDisposed)
            assertEquals(2, builds)
            binding.dispose()
            assertThrows(ViewModelError::class.java) { callback() }
            assertEquals(2, builds)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun fixedBinding_doesNotFollowReassignment_butResolvesNewGenerations() {
        val firstBinding = ViewModelBinding()
        val secondBinding = ViewModelBinding()
        var currentBinding = firstBinding
        val spec = viewModelSpec { DelegateModel(1) }
        try {
            val fixed by currentBinding.readViewModel(spec)
            val deferred by readViewModel(spec) { currentBinding }
            val first = fixed
            assertSame(first, deferred)
            assertFalse(first.hasListeners)
            currentBinding = secondBinding
            assertSame(first, fixed)
            assertNotSame(first, deferred)
            firstBinding.recycle(first)
            assertNotSame(first, fixed)
            assertSame(firstBinding.read(spec), fixed)
            firstBinding.dispose()
            assertThrows(ViewModelError::class.java) { fixed }
            assertFalse(deferred.isDisposed)
        } finally {
            firstBinding.dispose()
            secondBinding.dispose()
        }
    }

    @Test
    fun bindingProvider_isDeferred_andFollowsOwnerReplacement() {
        val firstBinding = ViewModelBinding()
        val secondBinding = ViewModelBinding()
        var currentBinding = firstBinding
        var resolutions = 0
        val spec = viewModelSpec { DelegateModel(1) }
        try {
            val model by readViewModel(spec) {
                resolutions++
                currentBinding
            }
            assertEquals(0, resolutions)
            val first = model
            assertFalse(first.hasListeners)
            firstBinding.dispose()
            currentBinding = secondBinding
            assertNotSame(first, model)
            assertEquals(2, resolutions)
            assertTrue(first.isDisposed)
        } finally {
            firstBinding.dispose()
            secondBinding.dispose()
        }
    }

    @Test
    fun parentDelegate_doesNotCreateDependenciesUntilAccess_andRejectsDisposedParent() {
        val binding = ViewModelBinding()
        val spec = viewModelSpec { DelegateParent() }
        try {
            val parent by readViewModel(spec) { binding }
            assertEquals(null, parent.dependencyBindingIfCreated)
            val child = parent.child
            val oldParent = parent
            binding.recycle(oldParent)
            assertTrue(child.isDisposed)
            assertThrows(ViewModelError::class.java) { oldParent.child }
            assertNotSame(child, parent.child)
        } finally {
            binding.dispose()
        }
    }
}

private class DelegateModel(val generation: Int) : ViewModel()
private val delegateChildSpec = viewModelSpec { DelegateModel(1) }
private class DelegateParent : ViewModel() {
    val child by readViewModel(delegateChildSpec) { viewModelBinding }
}
