package melu.viewmodel

import android.os.Looper
import androidx.annotation.MainThread

@MainThread
internal fun assertMainThread() {
    val mainLooper = try {
        Looper.getMainLooper()
    } catch (_: RuntimeException) {
        return
    }
    val currentLooper = try {
        Looper.myLooper()
    } catch (_: RuntimeException) {
        return
    }
    if (mainLooper != null && currentLooper != mainLooper) {
        throw IllegalStateException("AndroidViewModel API must be called on the main thread.")
    }
}
