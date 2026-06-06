package milu.viewmodel.example

import android.app.Application
import milu.viewmodel.ViewModel
import milu.viewmodel.ViewModelConfig

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ViewModel.initialize(
            config = ViewModelConfig(isLoggingEnabled = true),
        )
    }
}
