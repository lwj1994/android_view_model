package example.instagram.app

import android.app.Application
import milu.viewmodel.ViewModel

class InstagramApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ViewModel.initialize()
    }
}
