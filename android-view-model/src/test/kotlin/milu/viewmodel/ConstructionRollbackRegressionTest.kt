package milu.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class ConstructionRollbackRegressionTest {
    @Before
    fun setUp() {
        ViewModel.reset()
    }

    @After
    fun tearDown() {
        ViewModel.reset()
    }

    @Test
    fun failedConstructor_releasesResourcesOnceWithoutCallingPartialSubclassDisposal() {
        val releases = mutableListOf<String>()
        var businessDisposals = 0
        val failure = IllegalStateException("constructor failed")
        val spec = viewModelSpec {
            ConstructionResourceModel(
                cleanup = listOf({ releases += "first" }, { releases += "second" }),
                failure = failure,
                onBusinessDispose = { businessDisposals += 1 },
            )
        }
        val binding = ViewModelBinding()
        try {
            assertSame(failure, assertThrows(IllegalStateException::class.java) { binding.read(spec) })
            assertEquals(listOf("first", "second"), releases)
            assertEquals(0, businessDisposals)

            binding.dispose()
            ViewModel.reset()
            assertEquals(listOf("first", "second"), releases)
            assertEquals(0, businessDisposals)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun failedParent_releasesBothItsResourcesAndItsConstructedChildExactlyOnce() {
        var parentReleases = 0
        var childReleases = 0
        var childDisposals = 0
        val childSpec = viewModelSpec {
            ConstructionResourceModel(
                cleanup = listOf({ childReleases += 1 }),
                onBusinessDispose = { childDisposals += 1 },
            )
        }
        val parentSpec = viewModelSpec {
            FailingResourceParent(childSpec) { parentReleases += 1 }
        }
        val binding = ViewModelBinding()
        try {
            assertThrows(IllegalStateException::class.java) { binding.read(parentSpec) }
            assertEquals(1, parentReleases)
            assertEquals(1, childReleases)
            assertEquals(1, childDisposals)

            binding.dispose()
            ViewModel.reset()
            assertEquals(1, parentReleases)
            assertEquals(1, childReleases)
            assertEquals(1, childDisposals)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun successfulConstruction_retainsCleanupUntilNormalDisposal() {
        var releases = 0
        var businessDisposals = 0
        val spec = viewModelSpec {
            ConstructionResourceModel(
                cleanup = listOf({ releases += 1 }),
                onBusinessDispose = { businessDisposals += 1 },
            )
        }
        val binding = ViewModelBinding()
        try {
            val model = binding.read(spec)
            assertSame(model, binding.read(spec))
            assertEquals(0, releases)
            assertFalse(model.isDisposed)

            binding.dispose()
            assertTrue(model.isDisposed)
            assertEquals(1, releases)
            assertEquals(1, businessDisposals)
            binding.dispose()
            ViewModel.reset()
            assertEquals(1, releases)
            assertEquals(1, businessDisposals)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun untrackedDisposalFollowedByRollback_doesNotReleaseResourcesTwice() {
        var releases = 0
        var businessDisposals = 0
        val spec = viewModelSpec {
            ViewModel.reset()
            ConstructionResourceModel(
                cleanup = listOf({ releases += 1 }),
                onBusinessDispose = { businessDisposals += 1 },
            )
        }
        val binding = ViewModelBinding()
        try {
            assertThrows(ViewModelError::class.java) { binding.read(spec) }
            assertEquals(1, releases)
            assertEquals(1, businessDisposals)
            binding.dispose()
            ViewModel.reset()
            assertEquals(1, releases)
            assertEquals(1, businessDisposals)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun cleanupFailure_isReportedWithoutSkippingRemainingResourcesOrMaskingBuildFailure() {
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(config = ViewModelConfig(onError = { _, type -> errors += type }))
        var releases = 0
        val failure = IllegalStateException("constructor failed")
        val spec = viewModelSpec {
            ConstructionResourceModel(
                cleanup = listOf({ error("cleanup failed") }, { releases += 1 }),
                failure = failure,
            )
        }
        val binding = ViewModelBinding()
        try {
            assertSame(failure, assertThrows(IllegalStateException::class.java) { binding.read(spec) })
            assertEquals(1, releases)
            assertEquals(listOf(ErrorType.Dispose), errors)
        } finally {
            binding.dispose()
        }
    }

    @Test
    fun failedConstructor_cancelsItsScopeEvenWhenNoLifecycleHandleWasCreated() {
        val scopeJob = Job()
        var completions = 0
        scopeJob.invokeOnCompletion { completions += 1 }
        val spec = viewModelSpec { FailingScopeModel(scopeJob + Dispatchers.Unconfined) }
        val binding = ViewModelBinding()
        try {
            assertThrows(IllegalStateException::class.java) { binding.read(spec) }
            assertTrue(scopeJob.isCancelled)
            assertEquals(1, completions)
            binding.dispose()
            ViewModel.reset()
            assertEquals(1, completions)
        } finally {
            binding.dispose()
            scopeJob.cancel()
        }
    }
}

private class ConstructionResourceModel(
    cleanup: List<() -> Unit>,
    failure: Throwable? = null,
    private val onBusinessDispose: () -> Unit = {},
) : ViewModel() {
    init {
        cleanup.forEach(::addDispose)
        if (failure != null) throw failure
    }

    override fun dispose() {
        onBusinessDispose()
    }
}

private class FailingResourceParent(
    childSpec: ViewModelSpec<ConstructionResourceModel>,
    onRelease: () -> Unit,
) : ViewModel() {
    private val child by readViewModel(childSpec) { viewModelBinding }

    init {
        addDispose(onRelease)
        check(!child.isDisposed)
        error("parent constructor failed")
    }
}

private class FailingScopeModel(context: CoroutineContext) : ViewModel(context) {
    init {
        error("scope constructor failed")
    }
}
