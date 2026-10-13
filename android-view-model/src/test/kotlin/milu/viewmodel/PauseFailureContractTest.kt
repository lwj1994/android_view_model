package milu.viewmodel

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PauseFailureContractTest {
    @Before fun setUp() = ViewModel.reset()
    @After fun tearDown() = ViewModel.reset()

    @Test fun disposedProviderRejectsSubscriptionsAndIgnoresStateAndLegacyListeners() {
        val provider = MutablePauseSource()
        val events = mutableListOf<Boolean>()
        var removeSecond: () -> Unit = {}
        val removeFirst = provider.addOnPauseChanged { events += it; removeSecond() }
        removeSecond = provider.addOnPauseChanged { error("removed listener") }
        provider.setOnPauseChanged { events += it }
        provider.change(true)
        provider.change(true)
        assertEquals(listOf(true, true), events)
        removeFirst()
        provider.dispose()
        provider.setOnPauseChanged { error("disposed listener") }
        provider.change(false)
        assertTrue(provider.isPaused)
        assertThrows(ViewModelError::class.java) { provider.addOnPauseChanged {} }
    }

    @Test fun failedLegacySubscriptionRollsBackAndCanBeRetried() {
        var attempt = 0
        val provider = object : ViewModelBindingPauseProvider {
            override val isPaused = false
            override fun setOnPauseChanged(listener: (Boolean) -> Unit) {
                if (++attempt == 1) error("subscription rejected")
            }
            override fun dispose() = Unit
        }
        val controller = PauseAwareController({}, {})
        try {
            assertThrows(IllegalStateException::class.java) { controller.addProvider(provider) }
            assertFalse(controller.isPaused)
            controller.addProvider(provider)
            assertEquals(2, attempt)
            controller.removeProvider(provider)
            assertEquals(3, attempt)
        } finally { controller.dispose() }
    }

    @Test fun failedOwnedSubscriptionDisposesItsSourceAndReportsUnsubscribeErrors() {
        var disposed = 0
        val rejected = object : ViewModelBindingPauseProvider {
            override val isPaused = false
            override fun setOnPauseChanged(listener: (Boolean) -> Unit) = error("install")
            override fun dispose() { disposed++ }
        }
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(ViewModelConfig(onError = { _, type -> errors += type }))
        val controller = PauseAwareController({}, {})
        var installations = 0
        val unsubscribeFailure = object : ViewModelBindingPauseProvider {
            override val isPaused = false
            override fun setOnPauseChanged(listener: (Boolean) -> Unit) {
                if (++installations > 1) error("unsubscribe")
            }
            override fun dispose() { disposed++ }
        }
        try {
            assertThrows(IllegalStateException::class.java) { controller.addProvider(rejected, true) }
            assertEquals(1, disposed)
            controller.addProvider(unsubscribeFailure, true)
            controller.removeProvider(unsubscribeFailure)
            assertEquals(2, disposed)
            assertEquals(listOf(ErrorType.PauseResume), errors)
        } finally { controller.dispose() }
    }

    @Test fun synchronousSubscriptionCallbackCanDisposeControllerDuringInstallation() {
        lateinit var controller: PauseAwareController
        var installs = 0
        var disposed = 0
        val provider = object : ViewModelBindingPauseProvider {
            override val isPaused = true
            override fun setOnPauseChanged(listener: (Boolean) -> Unit) {
                installs++
                listener(true)
            }
            override fun dispose() { disposed++ }
        }
        controller = PauseAwareController(onPause = { controller.dispose() }, onResume = { error("disposed must not resume") })
        controller.addProvider(provider, true)
        assertFalse(controller.isPaused)
        assertEquals(1, disposed)
        assertEquals(2, installs)
    }

    @Test fun exceptionAfterSynchronousRemovalDoesNotDisposeRegistrationTwice() {
        lateinit var controller: PauseAwareController
        var first = true
        var disposals = 0
        val provider = object : ViewModelBindingPauseProvider {
            override val isPaused = true
            override fun setOnPauseChanged(listener: (Boolean) -> Unit) {
                if (first) {
                    first = false
                    listener(true)
                    error("failed after removal")
                }
            }
            override fun dispose() { disposals++ }
        }
        controller = PauseAwareController({ controller.removeProvider(provider) }, {})
        try {
            assertThrows(IllegalStateException::class.java) { controller.addProvider(provider, true) }
            assertFalse(controller.isPaused)
            assertEquals(1, disposals)
        } finally { controller.dispose() }
    }
}

private class MutablePauseSource : BasePauseProvider() {
    fun change(paused: Boolean) = setPaused(paused)
}
