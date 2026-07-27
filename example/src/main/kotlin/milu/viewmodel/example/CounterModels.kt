package milu.viewmodel.example

import milu.viewmodel.StateViewModel
import milu.viewmodel.ViewModel
import milu.viewmodel.viewModelSpec

data class CounterState(
    val count: Int = 0,
    val label: String = "Shared counter",
)

class CounterViewModel : StateViewModel<CounterState>(
    initialState = CounterState(),
    equals = { previous, current -> previous == current },
) {
    val analytics: AnalyticsViewModel
        get() = viewModelBinding.read(analyticsSpec)

    fun increment(source: String) {
        analytics.track("increment from $source")
        setState(state.copy(count = state.count + 1))
    }

    fun reset() {
        analytics.track("reset")
        setState(CounterState())
    }
}

class AnalyticsViewModel : ViewModel() {
    var lastEvent: String = "No event"
        private set

    fun track(event: String) {
        update { lastEvent = event }
    }
}

// The demo intentionally shares both modules across several Android host types.
// Ordinary feature-local specs should omit the key.
val analyticsSpec = viewModelSpec(
    key = "analytics",
) {
    AnalyticsViewModel()
}

val counterSpec = viewModelSpec(key = "counter") {
    CounterViewModel()
}

class PlainCounterController : AutoCloseable {
    private val scope = milu.viewmodel.ViewModelBindingScope()
    private val counter: CounterViewModel
        get() = scope.viewModelBinding.read(counterSpec)

    fun incrementFromPlainClass() {
        counter.increment("plain class")
    }

    override fun close() {
        scope.close()
    }
}
