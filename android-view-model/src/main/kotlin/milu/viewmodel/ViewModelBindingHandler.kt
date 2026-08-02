package milu.viewmodel

import androidx.annotation.MainThread
import java.util.IdentityHashMap

/** Dependency ownership and diagnostics attached to each [ViewModel]. */
@MainThread
public class ViewModelBindingHandler {
    private val dependencyBindings = mutableListOf<ViewModelBinding>()
    private val refSources = IdentityHashMap<ViewModelBinding, MutableSet<Any>>()
    private val ownerChangeListeners = linkedMapOf<Any, (
        owners: List<String>,
        previousPrimaryOwner: String?,
        primaryOwner: String?,
    ) -> Unit>()

    /** Ordered bindings that currently own this ViewModel. */
    public val owners: List<ViewModelBinding>
        get() = dependencyBindings.toList()

    /** Root/application owners, excluding internal parent-dependency scopes. */
    internal val externalOwners: List<ViewModelBinding>
        get() = dependencyBindings.filterNot { it.isDependencyBinding }

    /** Owners visible before the newly built parent has received its first direct ref. */
    internal val constructionExternalOwners: List<ViewModelBinding>
        get() {
            val current = externalOwners
            if (current.isNotEmpty()) return current
            val building = ViewModelBinding.currentBuilding
            return if (
                building == null ||
                building.isDisposed ||
                building.isDependencyBinding
            ) {
                emptyList()
            } else {
                listOf(building)
            }
        }

    internal val primaryOwner: ViewModelBinding?
        get() = dependencyBindings.firstOrNull()

    internal fun addOwnerChangeListener(
        listener: (List<String>, String?, String?) -> Unit,
    ): () -> Unit {
        val id = Any()
        ownerChangeListeners[id] = listener
        return { ownerChangeListeners.remove(id) }
    }

    internal fun addRef(
        binding: ViewModelBinding,
        source: Any = binding,
    ) {
        val previousPrimaryOwner = primaryOwner?.id
        val sources = refSources.getOrPut(binding) { identitySet() }
        if (!sources.add(source)) return
        if (sources.size == 1) {
            dependencyBindings += binding
            notifyOwnerChanges(previousPrimaryOwner)
        }
    }

    internal fun removeRef(
        binding: ViewModelBinding,
        source: Any = binding,
    ) {
        val previousPrimaryOwner = primaryOwner?.id
        val sources = refSources[binding] ?: return
        if (!sources.remove(source)) return
        if (sources.isNotEmpty()) return
        refSources.remove(binding)
        dependencyBindings.removeAll { it === binding }
        notifyOwnerChanges(previousPrimaryOwner)
    }

    internal fun dispose() {
        val previousPrimaryOwner = primaryOwner?.id
        dependencyBindings.clear()
        refSources.clear()
        notifyOwnerChanges(previousPrimaryOwner)
        ownerChangeListeners.clear()
    }

    public val binding: ViewModelBinding
        get() {
            assertMainThread()
            primaryOwner?.let { return it }
            ViewModelBinding.currentBuilding?.let { return it }
            throw ViewModelError(
                "No ViewModelBinding available. ViewModel must be created from a ViewModelBinding.",
            )
        }

    private fun notifyOwnerChanges(previousPrimaryOwner: String?) {
        if (ownerChangeListeners.isEmpty()) return
        val ids = dependencyBindings.map(ViewModelBinding::id)
        val currentPrimaryOwner = primaryOwner?.id
        ownerChangeListeners.toList().forEach { (id, listener) ->
            if (ownerChangeListeners[id] !== listener) return@forEach
            try {
                listener(ids, previousPrimaryOwner, currentPrimaryOwner)
            } catch (error: Throwable) {
                reportViewModelError(
                    error,
                    ErrorType.Listener,
                    "ViewModel owner diagnostics listener error",
                )
            }
        }
    }
}
