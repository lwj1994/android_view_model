package milu.viewmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CachedLookupContractTest {
    @Before
    fun setUp() {
        ViewModel.reset()
        ViewModel.initialize(config = ViewModelConfig(isDebugMode = true))
    }

    @After
    fun tearDown() {
        ViewModel.reset()
    }

    @Test
    fun unkeyedCachedEntryPoints_useCreationOrderAndFollowRecycleWithoutWarnings() {
        val owner = ViewModelBinding()
        val reader = ViewModelBinding()
        val olderSpec = viewModelSpec(key = "older") { CachedLookupViewModel() }
        val newerSpec = viewModelSpec(key = "newer") { CachedLookupViewModel() }
        try {
            val older = owner.read(olderSpec)
            assertCachedEntryPoints(older, reader)

            val newer = owner.read(newerSpec)
            assertSame(older, owner.read(olderSpec))
            assertCachedEntryPoints(newer, reader)
            assertWarningCount(0)

            owner.recycle(newer)
            assertTrue(newer.isDisposed)
            assertCachedEntryPoints(older, reader)

            val nextGeneration = owner.read(newerSpec)
            assertNotSame(newer, nextGeneration)
            assertCachedEntryPoints(nextGeneration, reader)
            assertWarningCount(0)
        } finally {
            reader.dispose()
            owner.dispose()
        }
    }

    @Test
    fun tagEntryPoints_matchByEqualityAndDeduplicateWarningsByObjectIdentity() {
        val owner = ViewModelBinding()
        val reader = ViewModelBinding()
        val olderTag = EqualityOnlyLookupTag("shared")
        val newerTag = EqualityOnlyLookupTag("shared")
        val queryTag = EqualityOnlyLookupTag("shared")
        val equalQueryTag = EqualityOnlyLookupTag("shared")
        val olderSpec = viewModelSpec(key = "older", tag = olderTag) { CachedLookupViewModel() }
        val newerSpec = viewModelSpec(key = "newer", tag = newerTag) { CachedLookupViewModel() }
        try {
            val older = owner.read(olderSpec)
            assertCachedEntryPoints(older, reader, tag = queryTag)
            assertWarningCount(0)

            val newer = owner.read(newerSpec)
            owner.read(viewModelSpec(key = "unrelated") { CachedLookupViewModel() })
            assertSame(older, owner.read(olderSpec))
            assertFalse(ViewModel.config.isLoggingEnabled)
            assertCachedEntryPoints(newer, reader, tag = queryTag)
            assertWarningCount(1)

            // Equality selects the same handles; distinct query objects each
            // receive their own warning even when normal logging is disabled.
            assertCachedEntryPoints(newer, reader, tag = equalQueryTag)
            assertWarningCount(2)

            owner.recycle(newer)
            assertCachedEntryPoints(older, reader, tag = queryTag)
            assertWarningCount(2)
            listOf(olderTag, newerTag, queryTag, equalQueryTag).forEach { tag ->
                assertEquals(0, tag.hashCalls)
            }
        } finally {
            reader.dispose()
            owner.dispose()
        }
    }

    @Test
    fun tagBatchEntryPoints_returnAllMatchesWithoutAmbiguityWarnings() {
        val owner = ViewModelBinding()
        val reader = ViewModelBinding()
        val tag = EqualityOnlyLookupTag("shared")
        val queryTag = EqualityOnlyLookupTag("shared")
        val olderSpec = viewModelSpec(key = "older", tag = tag) { CachedLookupViewModel() }
        val newerSpec = viewModelSpec(key = "newer", tag = tag) { CachedLookupViewModel() }
        try {
            val older = owner.read(olderSpec)
            val newer = owner.read(newerSpec)
            owner.read(viewModelSpec(key = "unrelated", tag = "other") { CachedLookupViewModel() })
            assertSame(older, owner.read(olderSpec))

            assertEquals(listOf(newer, older), reader.readCachesByTag<CachedLookupViewModel>(queryTag))
            assertEquals(listOf(newer, older), reader.watchCachesByTag<CachedLookupViewModel>(queryTag))
            assertWarningCount(0)

            // A batch query must not consume the later single-result warning.
            assertSame(newer, reader.readCached<CachedLookupViewModel>(tag = queryTag))
            assertWarningCount(1)
            assertEquals(0, tag.hashCalls)
            assertEquals(0, queryTag.hashCalls)
        } finally {
            reader.dispose()
            owner.dispose()
        }
    }

    @Test
    fun keyEntryPoints_takePriorityAndOnlyWarnWhenFallingBackToAmbiguousTag() {
        val owner = ViewModelBinding()
        val reader = ViewModelBinding()
        val tag = EqualityOnlyLookupTag("shared")
        val otherTag = EqualityOnlyLookupTag("shared")
        val keyedSpec = viewModelSpec(key = "target", tag = "different-tag") { CachedLookupViewModel() }
        val olderSpec = viewModelSpec(key = "older", tag = tag) { CachedLookupViewModel() }
        val newerSpec = viewModelSpec(key = "newer", tag = tag) { CachedLookupViewModel() }
        try {
            val keyed = owner.read(keyedSpec)
            owner.read(olderSpec)
            val newer = owner.read(newerSpec)

            assertSame(keyed, owner.watch(keyedSpec))
            assertCachedEntryPoints(keyed, reader, key = "target", tag = tag)
            assertCachedEntryPoints(keyed, reader, key = "target")
            assertWarningCount(0)

            assertCachedEntryPoints(newer, reader, key = "missing", tag = tag)
            assertWarningCount(1)
            assertCachedEntryPoints(newer, reader, key = "missing-again", tag = otherTag)
            assertWarningCount(2)
            assertEquals(0, tag.hashCalls)
            assertEquals(0, otherTag.hashCalls)
        } finally {
            reader.dispose()
            owner.dispose()
        }
    }

    @Test
    fun warnings_areScopedToResolvedTypeAndClearedByCompleteReset() {
        val owner = ViewModelBinding()
        val reader = ViewModelBinding()
        val tag = EqualityOnlyLookupTag("shared")
        val firstSpec = viewModelSpec(key = "first", tag = tag) { CachedLookupViewModel() }
        val secondSpec = viewModelSpec(key = "second", tag = tag) { CachedLookupViewModel() }
        val otherFirstSpec = viewModelSpec(key = "first", tag = tag) { OtherCachedLookupViewModel() }
        val otherSecondSpec = viewModelSpec(key = "second", tag = tag) { OtherCachedLookupViewModel() }
        try {
            owner.read(firstSpec)
            val previous = owner.read(secondSpec)
            owner.read(otherFirstSpec)
            val other = owner.read(otherSecondSpec)

            assertCachedEntryPoints(previous, reader, tag = tag)
            assertCachedEntryPoints(other, reader, tag = tag)
            assertWarningCount(2)

            ViewModel.reset()
            assertTrue(previous.isDisposed)
            assertTrue(other.isDisposed)
            assertEquals(0, InstanceManager.debugStoreCount)
            assertWarningCount(0)
            assertFalse(ViewModel.config.isDebugMode)
            ViewModel.initialize(config = ViewModelConfig(isDebugMode = true))

            owner.read(firstSpec)
            val next = owner.read(secondSpec)
            assertNotSame(previous, next)
            assertCachedEntryPoints(next, reader, tag = tag)
            assertWarningCount(1)
            assertEquals(0, tag.hashCalls)
        } finally {
            reader.dispose()
            owner.dispose()
        }
    }

    @Test
    fun hostDebugModeDisabled_suppressesWarningsForDirectAndFallbackTagQueries() {
        ViewModel.reset()
        ViewModel.initialize(config = ViewModelConfig(isDebugMode = false))
        val owner = ViewModelBinding()
        val reader = ViewModelBinding()
        val tag = EqualityOnlyLookupTag("shared")
        val olderSpec = viewModelSpec(key = "older", tag = tag) { CachedLookupViewModel() }
        val newerSpec = viewModelSpec(key = "newer", tag = tag) { CachedLookupViewModel() }
        try {
            owner.read(olderSpec)
            val newer = owner.read(newerSpec)

            assertCachedEntryPoints(newer, reader, tag = tag)
            assertCachedEntryPoints(newer, reader, key = "missing", tag = tag)
            assertWarningCount(0)
            assertEquals(0, tag.hashCalls)
        } finally {
            reader.dispose()
            owner.dispose()
        }
    }

    @Test
    fun missingCachedQueries_doNotCreateViewModelsOrWarn() {
        var builds = 0
        val spec = viewModelSpec(key = "available", tag = "available") {
            builds += 1
            CachedLookupViewModel()
        }
        val binding = ViewModelBinding()
        try {
            assertThrows(ViewModelError::class.java) { ViewModel.readCached<CachedLookupViewModel>() }
            assertThrows(ViewModelError::class.java) { binding.readCached<CachedLookupViewModel>() }
            assertThrows(ViewModelError::class.java) { binding.watchCached<CachedLookupViewModel>() }
            assertNull(ViewModel.maybeReadCached<CachedLookupViewModel>())
            assertNull(binding.maybeReadCached<CachedLookupViewModel>())
            assertNull(binding.maybeWatchCached<CachedLookupViewModel>())
            assertEquals(0, builds)

            binding.read(spec)
            assertNull(ViewModel.maybeReadCached<CachedLookupViewModel>(tag = "missing"))
            assertNull(binding.maybeReadCached<CachedLookupViewModel>(tag = "missing"))
            assertNull(binding.maybeWatchCached<CachedLookupViewModel>(tag = "missing"))
            assertNull(ViewModel.maybeReadCached<CachedLookupViewModel>(key = "missing", tag = "missing"))
            assertNull(binding.maybeReadCached<CachedLookupViewModel>(key = "missing", tag = "missing"))
            assertNull(binding.maybeWatchCached<CachedLookupViewModel>(key = "missing", tag = "missing"))
            assertTrue(binding.readCachesByTag<CachedLookupViewModel>("missing").isEmpty())
            assertTrue(binding.watchCachesByTag<CachedLookupViewModel>("missing").isEmpty())
            assertEquals(1, builds)
            assertWarningCount(0)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun storeCounts_trackCacheHitsRecyclingAndDisposalWithoutHashingTags() {
        val store = Store(Any::class)
        val tag = EqualityOnlyLookupTag("shared")
        val queryTag = EqualityOnlyLookupTag("shared")
        fun resolve(key: String, valueTag: Any? = null): InstanceHandle<Any> = store.getHandle(
            InstanceFactory(builder = { Any() }, arg = InstanceArg(key = key, tag = valueTag)),
        )
        try {
            assertEquals(0, store.countInstances())
            assertEquals(0, store.countInstances(queryTag))
            val older = resolve("older", tag)
            val untagged = resolve("untagged")
            val newer = resolve("newer", tag)
            assertEquals(3, store.countInstances())
            assertEquals(2, store.countInstances(queryTag))
            assertEquals(0, store.countInstances("missing"))
            assertSame(older, resolve("older", "ignored-on-hit"))
            assertEquals(3, store.countInstances())
            assertEquals(2, store.countInstances(queryTag))
            assertSame(newer, store.findNewest(queryTag))
            assertSame(newer, store.findNewest())

            assertTrue(store.tryRecycle(newer.requireInstance()))
            assertEquals(2, store.countInstances())
            assertEquals(1, store.countInstances(queryTag))
            assertSame(older, store.findNewest(queryTag))
            assertSame(untagged, store.findNewest())

            assertTrue(store.tryRecycle(untagged.requireInstance()))
            assertEquals(1, store.countInstances())
            assertEquals(1, store.countInstances(queryTag))
            assertTrue(store.tryRecycle(older.requireInstance()))
            assertEquals(0, store.countInstances())
            assertEquals(0, store.countInstances(queryTag))
            assertNull(store.findNewest())

            resolve("last", tag)
            store.dispose()
            assertEquals(0, store.countInstances())
            assertEquals(0, store.countInstances(queryTag))
            assertEquals(0, tag.hashCalls)
            assertEquals(0, queryTag.hashCalls)
        } finally {
            store.dispose()
        }
    }

    private inline fun <reified VM : ViewModel> assertCachedEntryPoints(
        expected: VM,
        binding: ViewModelBinding,
        key: Any? = null,
        tag: Any? = null,
    ) {
        assertSame(expected, ViewModel.readCached<VM>(key = key, tag = tag))
        assertSame(expected, ViewModel.maybeReadCached<VM>(key = key, tag = tag))
        assertSame(expected, binding.readCached<VM>(key = key, tag = tag))
        assertSame(expected, binding.maybeReadCached<VM>(key = key, tag = tag))
        assertSame(expected, binding.watchCached<VM>(key = key, tag = tag))
        assertSame(expected, binding.maybeWatchCached<VM>(key = key, tag = tag))
    }

    private fun assertWarningCount(expectedCount: Int) {
        assertEquals(
            expectedCount,
            InstanceManager.debugAmbiguousTagLookupWarningCount,
        )
    }
}

private class CachedLookupViewModel : ViewModel()

private class OtherCachedLookupViewModel : ViewModel()

private class EqualityOnlyLookupTag(private val value: String) {
    var hashCalls = 0
        private set

    override fun equals(other: Any?): Boolean =
        other is EqualityOnlyLookupTag && value == other.value

    override fun hashCode(): Int {
        hashCalls += 1
        error("Tag hashCode must not be called by lookups or diagnostics")
    }

    // Keep Object.toString(): formatting it normally would call hashCode().
}
