package milu.viewmodel

import androidx.annotation.MainThread

/**
 * Dependency resolver attached to each [ViewModel].
 */
@MainThread
public class ViewModelBindingHandler {
    private val dependencyBindings = mutableListOf<ViewModelBinding>()

    internal fun addRef(binding: ViewModelBinding) {
        if (dependencyBindings.none { it === binding }) {
            dependencyBindings += binding
        }
    }

    internal fun removeRef(binding: ViewModelBinding) {
        dependencyBindings.removeAll { it === binding }
    }

    internal fun dispose() {
        dependencyBindings.clear()
    }

    public val binding: ViewModelBinding
        get() {
            assertMainThread()
            dependencyBindings.firstOrNull()?.let { return it }
            ViewModelBinding.currentBuilding?.let { return it }
            throw IllegalStateException(
                "No ViewModelBinding available. ViewModel must be created from a ViewModelBinding.",
            )
        }
}
