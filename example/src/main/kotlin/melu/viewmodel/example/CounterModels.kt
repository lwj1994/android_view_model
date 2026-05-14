package melu.viewmodel.example

import melu.viewmodel.StateViewModel
import melu.viewmodel.ViewModel
import melu.viewmodel.viewModelSpec

data class CounterState(
    val count: Int = 0,
    val label: String = "Shared counter",
)

class CounterViewModel : StateViewModel<CounterState>(
    initialState = CounterState(),
    equals = { previous, current -> previous == current },
) {
    val analytics: AnalyticsViewModel = viewModelBinding.read(analyticsSpec)

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

val analyticsSpec = viewModelSpec(
    key = "analytics",
    aliveForever = true,
) {
    AnalyticsViewModel()
}

val counterSpec = viewModelSpec(key = "counter") {
    CounterViewModel()
}

class PlainCounterController : AutoCloseable {
    private val scope = melu.viewmodel.ViewModelBindingScope()
    private val counter = scope.viewModelBinding.read(counterSpec)

    fun incrementFromPlainClass() {
        counter.increment("plain class")
    }

    override fun close() {
        scope.close()
    }
}
