package milu.viewmodel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PauseProviderSharingTest {
    @Before
    fun setUp() {
        ViewModel.reset()
    }

    @After
    fun tearDown() {
        ViewModel.reset()
    }

    @Test
    fun sharedProvider_resumesBothBindingsAndRemovingOneLeavesTheOtherSubscribed() {
        val provider = SharingPauseProvider()
        var firstUpdates = 0
        var secondUpdates = 0
        val first = ViewModelBinding { firstUpdates += 1 }
        val second = ViewModelBinding { secondUpdates += 1 }
        val spec = viewModelSpec(key = "shared-pause-model") { PauseSharingModel() }
        try {
            first.addPauseProvider(provider)
            first.addPauseProvider(provider)
            second.addPauseProvider(provider)
            val model = first.watch(spec)
            assertSame(model, second.watch(spec))

            provider.change(true)
            model.notifyListeners()
            model.notifyListeners()
            assertEquals(0, firstUpdates)
            assertEquals(0, secondUpdates)

            provider.change(false)
            assertEquals(1, firstUpdates)
            assertEquals(1, secondUpdates)

            provider.change(true)
            model.notifyListeners()
            first.removePauseProvider(provider)
            assertFalse(first.isPaused)
            assertTrue(second.isPaused)
            assertEquals(2, firstUpdates)
            assertEquals(1, secondUpdates)
            assertEquals(0, provider.disposeCalls)

            provider.change(false)
            assertEquals(2, firstUpdates)
            assertEquals(2, secondUpdates)
            model.notifyListeners()
            assertEquals(3, firstUpdates)
            assertEquals(3, secondUpdates)
        } finally {
            first.dispose()
            second.dispose()
            provider.dispose()
        }
    }

    @Test
    fun disposingOneBinding_doesNotDisposeItsBorrowedSourceOrStopTheOtherBinding() {
        val provider = SharingPauseProvider()
        var firstUpdates = 0
        var secondUpdates = 0
        val first = ViewModelBinding { firstUpdates += 1 }
        val second = ViewModelBinding { secondUpdates += 1 }
        val spec = viewModelSpec(key = "surviving-pause-model") { PauseSharingModel() }
        try {
            first.addPauseProvider(provider)
            second.addPauseProvider(provider)
            first.watch(spec)
            val model = second.watch(spec)
            provider.change(true)
            model.notifyListeners()

            first.dispose()
            assertEquals(0, provider.disposeCalls)
            assertFalse(model.isDisposed)
            provider.change(false)
            assertEquals(0, firstUpdates)
            assertEquals(1, secondUpdates)

            provider.change(true)
            model.notifyListeners()
            assertEquals(1, secondUpdates)
            provider.change(false)
            assertEquals(2, secondUpdates)

            second.dispose()
            assertTrue(model.isDisposed)
            assertEquals(0, provider.disposeCalls)
        } finally {
            first.dispose()
            second.dispose()
            provider.dispose()
        }
    }

    @Test
    fun legacySetter_doesNotReplaceIndependentSubscriptions() {
        val provider = SharingPauseProvider()
        val legacyChanges = mutableListOf<Boolean>()
        val replacedChanges = mutableListOf<Boolean>()
        val firstChanges = mutableListOf<Boolean>()
        val secondChanges = mutableListOf<Boolean>()
        val cancelFirst = provider.addOnPauseChanged { firstChanges += it }
        val cancelSecond = provider.addOnPauseChanged { secondChanges += it }
        try {
            provider.setOnPauseChanged { replacedChanges += it }
            provider.setOnPauseChanged { legacyChanges += it }
            provider.change(true)
            cancelFirst()
            cancelFirst()
            provider.change(false)

            assertTrue(replacedChanges.isEmpty())
            assertEquals(listOf(true, false), legacyChanges)
            assertEquals(listOf(true), firstChanges)
            assertEquals(listOf(true, false), secondChanges)
        } finally {
            cancelFirst()
            cancelSecond()
            provider.dispose()
        }
    }

    @Test
    fun throwingSubscriber_doesNotPreventOtherSubscribersOrBindingCatchUp() {
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(config = ViewModelConfig(onError = { _, type -> errors += type }))
        val provider = SharingPauseProvider()
        val changes = mutableListOf<Boolean>()
        val cancelThrowing = provider.addOnPauseChanged { error("subscriber failed") }
        val cancelRecording = provider.addOnPauseChanged { changes += it }
        var updates = 0
        val binding = ViewModelBinding { updates += 1 }
        val spec = viewModelSpec { PauseSharingModel() }
        try {
            binding.addPauseProvider(provider)
            val model = binding.watch(spec)
            provider.setOnPauseChanged { error("legacy callback failed") }
            provider.change(true)
            model.notifyListeners()
            assertEquals(0, updates)
            provider.change(false)

            assertEquals(1, updates)
            assertEquals(listOf(true, false), changes)
            assertEquals(List(4) { ErrorType.PauseResume }, errors)
        } finally {
            binding.dispose()
            cancelThrowing()
            cancelRecording()
            provider.dispose()
        }
    }

    @Test
    fun legacyCustomProvider_fansOutAndReleasesItsBridgeAfterTheFinalSubscription() {
        val provider = LegacySharingPauseProvider()
        val firstTransitions = mutableListOf<String>()
        val secondTransitions = mutableListOf<String>()
        val first = PauseAwareController(
            onPause = { firstTransitions += "pause" },
            onResume = { firstTransitions += "resume" },
        )
        val second = PauseAwareController(
            onPause = { secondTransitions += "pause" },
            onResume = { secondTransitions += "resume" },
        )
        try {
            first.addProvider(provider)
            second.addProvider(provider)
            assertEquals(1, provider.setterCalls)
            provider.change(true)
            provider.change(false)
            assertEquals(listOf("pause", "resume"), firstTransitions)
            assertEquals(listOf("pause", "resume"), secondTransitions)

            first.removeProvider(provider)
            assertEquals(1, provider.setterCalls)
            provider.change(true)
            assertEquals(listOf("pause", "resume"), firstTransitions)
            assertEquals(listOf("pause", "resume", "pause"), secondTransitions)

            second.removeProvider(provider)
            assertEquals(2, provider.setterCalls)
            assertEquals(0, provider.disposeCalls)
            provider.change(false)
            // A fresh subscription must install a new bridge, not reuse a retained one.
            first.addProvider(provider)
            assertEquals(3, provider.setterCalls)
            provider.change(true)
            assertEquals(listOf("pause", "resume", "pause"), firstTransitions)
            assertEquals(listOf("pause", "resume", "pause", "resume"), secondTransitions)
        } finally {
            first.dispose()
            second.dispose()
            provider.dispose()
        }
    }

    @Test
    fun legacyCustomProvider_isolatesSubscriberFailuresAndCancelsEachRegistrationSeparately() {
        val errors = mutableListOf<ErrorType>()
        ViewModel.initialize(config = ViewModelConfig(onError = { _, type -> errors += type }))
        val provider = LegacySharingPauseProvider()
        val changes = mutableListOf<Boolean>()
        val cancelThrowing = provider.addOnPauseChanged { error("legacy provider subscriber failed") }
        val cancelRecording = provider.addOnPauseChanged { changes += it }
        try {
            provider.change(true)
            assertEquals(listOf(true), changes)
            assertEquals(listOf(ErrorType.PauseResume), errors)

            cancelThrowing()
            cancelThrowing()
            provider.change(false)
            assertEquals(listOf(true, false), changes)
            assertEquals(listOf(ErrorType.PauseResume), errors)
            cancelRecording()
            assertEquals(0, provider.disposeCalls)
        } finally {
            cancelThrowing()
            cancelRecording()
            provider.dispose()
        }
    }
}

private class PauseSharingModel : ViewModel()

private class SharingPauseProvider : BasePauseProvider() {
    var disposeCalls = 0
        private set

    fun change(paused: Boolean) = setPaused(paused)

    override fun dispose() {
        disposeCalls += 1
        super.dispose()
    }
}

/** Deliberately uses only the original single-callback provider interface. */
private class LegacySharingPauseProvider : ViewModelBindingPauseProvider {
    override var isPaused: Boolean = false
        private set
    var setterCalls = 0
        private set
    var disposeCalls = 0
        private set
    private var listener: ((Boolean) -> Unit)? = null

    override fun setOnPauseChanged(listener: (Boolean) -> Unit) {
        setterCalls += 1
        this.listener = listener
    }

    fun change(paused: Boolean) {
        if (isPaused == paused) return
        isPaused = paused
        listener?.invoke(paused)
    }

    override fun dispose() {
        disposeCalls += 1
        listener = null
    }

    override fun equals(other: Any?): Boolean = error("Provider identity must not use equals")

    override fun hashCode(): Int = error("Provider identity must not use hashCode")
}
