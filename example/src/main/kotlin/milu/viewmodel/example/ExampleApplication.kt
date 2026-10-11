package milu.viewmodel.example

import android.app.Application
import android.content.pm.ApplicationInfo
import milu.viewmodel.ViewModel
import milu.viewmodel.ViewModelConfig

class ExampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ViewModel.initialize(
            config = ViewModelConfig(
                isLoggingEnabled = true,
                isDebugMode = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            ),
        )
    }
}
