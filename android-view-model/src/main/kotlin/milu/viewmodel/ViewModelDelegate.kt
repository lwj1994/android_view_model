package milu.viewmodel

import androidx.annotation.MainThread
import kotlin.reflect.KProperty

/**
 * Read-only delegate holding only resolution logic. Every access resolves within its ownership boundary; no VM is cached.
 * Use on the main thread while its owner is alive; never pass delegates or resolved instances across owners.
 */
@MainThread
public class ViewModelDelegate<VM : ViewModel> internal constructor(
    private val resolve: () -> VM,
) {
    public operator fun getValue(thisRef: Any?, property: KProperty<*>): VM {
        assertMainThread()
        return resolve()
    }
}

/** Defers binding lookup for hosts whose binding can change, such as Fragment views. */
@MainThread
public fun <VM : ViewModel> watchViewModel(
    factory: ViewModelFactory<VM>,
    binding: () -> ViewModelBinding,
): ViewModelDelegate<VM> {
    assertMainThread()
    return ViewModelDelegate { binding().watch(factory) }
}

/** Preserves watch ownership/disposal semantics without observing VM notifications. */
@MainThread
public fun <VM : ViewModel> readViewModel(
    factory: ViewModelFactory<VM>,
    binding: () -> ViewModelBinding,
): ViewModelDelegate<VM> {
    assertMainThread()
    return ViewModelDelegate { binding().read(factory) }
}

