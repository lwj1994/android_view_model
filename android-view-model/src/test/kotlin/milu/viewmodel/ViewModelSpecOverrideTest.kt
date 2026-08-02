package milu.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ViewModelSpecOverrideTest {
    @Before
    fun setUp() {
        ViewModel.reset()
    }

    @After
    fun tearDown() {
        ViewModel.reset()
    }

    @Test
    fun legacyProxy_canClearNullableMetadataAndDisableRetention() {
        val base = viewModelSpec(
            key = "base-key",
            tag = "base-tag",
            aliveForever = true,
        ) { OverrideViewModel("base") }
        val override = viewModelSpec { OverrideViewModel("override") }
        base.setProxy(override)

        assertNull(base.key())
        assertNull(base.tag())
        assertFalse(base.aliveForever())

        val binding = ViewModelBinding()
        val viewModel = binding.read(base)
        assertEquals("override", viewModel.label)
        binding.dispose()
        assertTrue(viewModel.isDisposed)

        base.clearProxy()
        assertEquals("base-key", base.key())
        assertEquals("base-tag", base.tag())
        assertTrue(base.aliveForever())
    }

    @Test
    fun argumentProxy_canClearNullableMetadataAndDisableRetention() {
        val base = viewModelSpecWithArg<OverrideViewModel, String>(
            builder = { OverrideViewModel("base-$it") },
            key = { "base-$it" },
            tag = { "tag-$it" },
            aliveForever = { true },
        )
        val override = viewModelSpecWithArg<OverrideViewModel, String>(
            builder = { OverrideViewModel("override-$it") },
        )
        base.setProxy(override)
        val active = base("value")

        assertNull(active.key())
        assertNull(active.tag())
        assertFalse(active.aliveForever())
        assertEquals("override-value", resolveLabel(active))

        base.clearProxy()
    }

    @Test
    fun overrideWith_isAvailableForTwoThroughFourArgumentSpecs() {
        val arg2 = viewModelSpecWithArg2<OverrideViewModel, String, String>(
            builder = { first, second -> OverrideViewModel("base-$first-$second") },
        )
        val arg2Override = viewModelSpecWithArg2<OverrideViewModel, String, String>(
            builder = { first, second -> OverrideViewModel("override-$first-$second") },
        )
        val restoreArg2 = arg2.overrideWith(arg2Override)
        assertEquals("override-a-b", resolveLabel(arg2("a", "b")))
        restoreArg2()
        assertEquals("base-a-b", resolveLabel(arg2("a", "b")))

        val arg3 = viewModelSpecWithArg3<OverrideViewModel, String, String, String>(
            builder = { first, second, third -> OverrideViewModel("base-$first-$second-$third") },
        )
        val arg3Override = viewModelSpecWithArg3<OverrideViewModel, String, String, String>(
            builder = { first, second, third -> OverrideViewModel("override-$first-$second-$third") },
        )
        val restoreArg3 = arg3.overrideWith(arg3Override)
        assertEquals("override-a-b-c", resolveLabel(arg3("a", "b", "c")))
        restoreArg3()
        assertEquals("base-a-b-c", resolveLabel(arg3("a", "b", "c")))

        val arg4 = viewModelSpecWithArg4<OverrideViewModel, String, String, String, String>(
            builder = { first, second, third, fourth ->
                OverrideViewModel("base-$first-$second-$third-$fourth")
            },
        )
        val arg4Override = viewModelSpecWithArg4<OverrideViewModel, String, String, String, String>(
            builder = { first, second, third, fourth ->
                OverrideViewModel("override-$first-$second-$third-$fourth")
            },
        )
        val restoreArg4 = arg4.overrideWith(arg4Override)
        assertEquals("override-a-b-c-d", resolveLabel(arg4("a", "b", "c", "d")))
        restoreArg4()
        assertEquals("base-a-b-c-d", resolveLabel(arg4("a", "b", "c", "d")))
    }

    @Test
    fun overrideWith_isNestedIdempotentAndSupportsOutOfOrderRestore() {
        val base = viewModelSpec { OverrideViewModel("base") }
        val first = viewModelSpec { OverrideViewModel("first") }
        val second = viewModelSpec { OverrideViewModel("second") }

        val restoreFirst = base.overrideWith(first)
        val restoreSecond = base.overrideWith(second)
        assertEquals("second", resolveLabel(base))

        restoreFirst()
        assertEquals("second", resolveLabel(base))

        restoreSecond()
        restoreSecond()
        assertEquals("base", resolveLabel(base))
    }

    @Test
    fun runWithOverride_isolatesNestedAndOverlappingCoroutines() = runTest {
        val base = viewModelSpec { OverrideViewModel("base") }
        val first = viewModelSpec { OverrideViewModel("first") }
        val second = viewModelSpec { OverrideViewModel("second") }
        val inner = viewModelSpec { OverrideViewModel("inner") }
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val observations = mutableListOf<String>()

        val firstRun = async {
            base.runWithOverride(first) {
                assertEquals("first", resolveLabel(base))
                base.runWithOverride(inner) {
                    assertEquals("inner", resolveLabel(base))
                }
                assertEquals("first", resolveLabel(base))
                firstEntered.complete(Unit)
                secondEntered.await()
                observations += "first:${resolveLabel(base)}"
                releaseSecond.complete(Unit)
            }
        }
        val secondRun = async {
            base.runWithOverride(second) {
                firstEntered.await()
                secondEntered.complete(Unit)
                releaseSecond.await()
                observations += "second:${resolveLabel(base)}"
            }
        }

        awaitAll(firstRun, secondRun)
        assertEquals(listOf("first:first", "second:second"), observations)
        assertEquals("base", resolveLabel(base))
    }

    @Test
    fun runWithOverride_restoresAfterFailure() = runTest {
        val base = viewModelSpec { OverrideViewModel("base") }
        val override = viewModelSpec { OverrideViewModel("override") }

        val failure = runCatching {
            base.runWithOverride(override) {
                assertEquals("override", resolveLabel(base))
                error("failure")
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)

        assertEquals("base", resolveLabel(base))
    }
}

private class OverrideViewModel(val label: String) : ViewModel()

private fun resolveLabel(spec: ViewModelFactory<OverrideViewModel>): String {
    val binding = ViewModelBinding()
    return try {
        binding.read(spec).label
    } finally {
        binding.dispose()
    }
}
