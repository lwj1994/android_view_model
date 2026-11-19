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

    // A diagnostics listener receives the full owner snapshot; a dependency binding
    // receives only the owner that changed, because a shared ViewModel can be owned
    // by thousands of list cells and must not rebuild its owner list per change.
    private class OwnerListener(
        val snapshot: ((List<String>, String?, String?) -> Unit)? = null,
        val delta: ((Long, ViewModelBinding?, Boolean) -> Unit)? = null,
    )
    private val ownerChangeListeners = linkedMapOf<Any, OwnerListener>()

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
        listener: (owners: List<String>, previousPrimaryOwner: String?, primaryOwner: String?) -> Unit,
    ): () -> Unit = addListener(OwnerListener(snapshot = listener))

    /**
     * Adds an incremental owner listener for dependency bindings.
     *
     * `revision` is this handler's ownership revision for the change. `owner` is the
     * single binding that was added (`added = true`) or removed, or null when several
     * owners changed at once. A listener that has seen every previous revision can
     * apply the change directly; otherwise it must resynchronize from [externalOwners].
     */
    internal fun addOwnerDeltaListener(
        listener: (revision: Long, owner: ViewModelBinding?, added: Boolean) -> Unit,
    ): () -> Unit = addListener(OwnerListener(delta = listener))

    private fun addListener(listener: OwnerListener): () -> Unit {
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
        return ownerChange(previousPrimaryOwner, binding, added = true).also { if (notify) it() }
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
        return ownerChange(previousPrimaryOwner, binding, added = false).also { if (notify) it() }
    }

    internal fun clearRefs(notify: Boolean = true): (() -> Unit)? {
        if (refSources.isEmpty()) return null
        val previousPrimaryOwner = primaryOwner?.id
        refSources.clear()
        return ownerChange(previousPrimaryOwner, owner = null, added = false).also { if (notify) it() }
    }

    private fun ownerChange(
        previousPrimaryOwner: String?,
        owner: ViewModelBinding?,
        added: Boolean,
    ): () -> Unit {
        val revision = ++ownerRevision
        return {
            if (revision == ownerRevision) {
                notifyOwnerChanges(previousPrimaryOwner, revision, owner, added)
            }
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

    private fun notifyOwnerChanges(
        previousPrimaryOwner: String?,
        revision: Long,
        owner: ViewModelBinding?,
        added: Boolean,
    ) {
        if (ownerChangeListeners.isEmpty()) return
        // Built only for diagnostics listeners: O(owners) per change otherwise.
        var ids: List<String>? = null
        val currentPrimaryOwner = primaryOwner?.id
        ownerChangeListeners.toList().forEach { (id, listener) ->
            if (revision != ownerRevision) return
            if (ownerChangeListeners[id] !== listener) return@forEach
            try {
                val delta = listener.delta
                if (delta != null) {
                    delta(revision, owner, added)
                } else {
                    val snapshot = ids ?: owners.map(ViewModelBinding::id).also { ids = it }
                    listener.snapshot!!(snapshot, previousPrimaryOwner, currentPrimaryOwner)
                }
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
