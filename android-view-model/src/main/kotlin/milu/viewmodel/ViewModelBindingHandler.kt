package milu.viewmodel

import androidx.annotation.MainThread

/** Dependency ownership and diagnostics attached to each [ViewModel]. */
@MainThread
public class ViewModelBindingHandler {
    // Binding IDs are unique and immutable. One ordered ledger keeps direct-ref
    // checks constant-time without relying on a binding's equals/hashCode overrides.
    private class OwnerSources(val binding: ViewModelBinding) {
        val sources = identitySet<Any>()
    }
    private val refSources = linkedMapOf<String, OwnerSources>()
    private var ownerRevision = 0L
    private val ownerChangeListeners = linkedMapOf<Any, (
        owners: List<String>,
        previousPrimaryOwner: String?,
        primaryOwner: String?,
    ) -> Unit>()

    /** Ordered bindings that currently own this ViewModel. */
    public val owners: List<ViewModelBinding>
        get() = refSources.values.map { it.binding }

    /** Root/application owners, excluding internal parent-dependency scopes. */
    internal val externalOwners: List<ViewModelBinding>
        get() = owners.filterNot { it.isDependencyBinding }

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
        get() = refSources.values.firstOrNull()?.binding

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
        notify: Boolean = true,
    ): (() -> Unit)? {
        val previousPrimaryOwner = primaryOwner?.id
        val entry = refSources.getOrPut(binding.id) { OwnerSources(binding) }
        if (!entry.sources.add(source) || entry.sources.size != 1) return null
        return ownerChange(previousPrimaryOwner).also { if (notify) it() }
    }

    /** Whether this binding owns the ViewModel through its own direct source. */
    internal fun hasDirectRef(binding: ViewModelBinding): Boolean =
        refSources[binding.id]?.sources?.contains(binding) == true

    internal fun removeRef(
        binding: ViewModelBinding,
        source: Any = binding,
        notify: Boolean = true,
    ): (() -> Unit)? {
        val previousPrimaryOwner = primaryOwner?.id
        val entry = refSources[binding.id] ?: return null
        if (!entry.sources.remove(source) || entry.sources.isNotEmpty()) return null
        refSources.remove(binding.id)
        return ownerChange(previousPrimaryOwner).also { if (notify) it() }
    }

    internal fun clearRefs(notify: Boolean = true): (() -> Unit)? {
        if (refSources.isEmpty()) return null
        val previousPrimaryOwner = primaryOwner?.id
        refSources.clear()
        return ownerChange(previousPrimaryOwner).also { if (notify) it() }
    }

    private fun ownerChange(previousPrimaryOwner: String?): () -> Unit {
        val revision = ++ownerRevision
        return {
            if (revision == ownerRevision) notifyOwnerChanges(previousPrimaryOwner, revision)
        }
    }

    internal fun dispose() {
        clearRefs()
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

    private fun notifyOwnerChanges(previousPrimaryOwner: String?, revision: Long) {
        if (ownerChangeListeners.isEmpty()) return
        val ids = owners.map(ViewModelBinding::id)
        val currentPrimaryOwner = primaryOwner?.id
        ownerChangeListeners.toList().forEach { (id, listener) ->
            if (revision != ownerRevision) return
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
