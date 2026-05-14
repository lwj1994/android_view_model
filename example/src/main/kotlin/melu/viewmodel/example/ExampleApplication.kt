package melu.viewmodel.example

import android.app.Application
import melu.viewmodel.ViewModel
import melu.viewmodel.ViewModelConfig

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ViewModel.initialize(
            config = ViewModelConfig(isLoggingEnabled = true),
        )
    }
}
