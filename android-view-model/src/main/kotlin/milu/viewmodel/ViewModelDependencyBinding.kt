package milu.viewmodel

import java.util.IdentityHashMap

/** Stable dependency/lifecycle scope owned by one parent ViewModel generation. */
internal class ViewModelDependencyBinding(
    private val parent: ViewModel,
    parentHandler: ViewModelBindingHandler,
) : ViewModelBinding() {
    override val isDependencyBinding: Boolean = true

    // Root owners mirrored onto every dependency, in the parent's owner order. Binding
    // IDs are unique, so an ordered map keeps membership checks and removals O(1): a
    // shared ViewModel can be owned by thousands of list cells.
    private val propagatedOwners = linkedMapOf<String, ViewModelBinding>()
    // Parent owner revision [propagatedOwners] reflects; null until the first change,
    // because construction may have used the building binding as a fallback owner.
    private var syncedRevision: Long? = null
    // Each attachment gets a token so old cleanup cannot release a replacement edge.
    private class DependencyEdge(val viewModel: ViewModel)
    private val dependencies = IdentityHashMap<InstanceHandle<*>, DependencyEdge>()
    private val removeOwnerListener: () -> Unit
    private var dependencyDisposed = false

    init {
        registerViewModelConstructionRollback(::dispose)
        parentHandler.constructionExternalOwners.forEach { propagatedOwners[it.id] = it }
        removeOwnerListener = parentHandler.addOwnerDeltaListener { revision, owner, added ->
            ownersChanged(parentHandler, revision, owner, added)
        }
    }

    override fun validateInstanceAttachment(
        handle: InstanceHandle<*>,
        viewModel: ViewModel,
    ) {
        requireAcyclicDependency(viewModel)
    }

    override fun handleInstanceAttached(
        handle: InstanceHandle<*>,
        viewModel: ViewModel,
    ): () -> Unit {
        val edge = DependencyEdge(viewModel)
        dependencies[handle] = edge
        return {
            propagatedOwners.values.toList().forEach { owner -> attachOwner(handle, edge, owner) }
        }
    }

    override fun handleInstanceDetached(
        handle: InstanceHandle<*>,
        viewModel: ViewModel,
    ): () -> Unit {
        val edge = dependencies.remove(handle)
        val owners = propagatedOwners.values.toList()
        super.handleInstanceDetached(handle, viewModel)
        return {
            if (edge != null) owners.forEach { owner -> detachOwner(handle, edge, owner) }
            notifyParentAfterDetach()
        }
    }

    override fun onViewModelUpdate(viewModel: ViewModel) {
        // watch forwards refresh notifications; business reactions use explicit listen*.
        if (!parent.isDisposed) parent.notifyListeners()
    }

    override fun onUpdate() {
        // Handle disposal is forwarded by the source-aware hooks.
    }

    override fun dispose() {
        if (dependencyDisposed) return
        dependencyDisposed = true
        removeOwnerListener()
        // Base disposal marks the binding disposed before releasing each tracked edge.
        super.dispose()
        propagatedOwners.clear()
        dependencies.clear()
    }

    private fun notifyParentAfterDetach() {
        if (
            dependencyDisposed ||
            parent.isDisposed ||
            InstanceManager.isResetting
        ) {
            return
        }
        // A detached dependency can change a value observed by business listeners.
        // Preserve every parent notification; only external root refreshes coalesce.
        parent.notifyListeners()
    }

    private fun requireAcyclicDependency(dependency: ViewModel) {
        val createsCycle = dependency === parent ||
            dependency.dependencyBindingIfCreated?.reaches(parent, identitySet()) == true
        if (!createsCycle) return
        throw ViewModelError(
            "Circular ViewModel dependency detected: " +
                "${parent::class.qualifiedName} -> ${dependency::class.qualifiedName}.",
        )
    }

    private fun reaches(
        target: ViewModel,
        visited: MutableSet<ViewModelDependencyBinding>,
    ): Boolean {
        if (!visited.add(this)) return false
        return dependencies.values.any { edge ->
            val dependency = edge.viewModel
            dependency === target ||
                dependency.dependencyBindingIfCreated?.reaches(target, visited) == true
        }
    }

    private fun ownersChanged(
        parentHandler: ViewModelBindingHandler,
        revision: Long,
        owner: ViewModelBinding?,
        added: Boolean,
    ) {
        if (dependencyDisposed) return
        val inStep = syncedRevision == revision - 1
        // Record the revision before child hooks can reenter with a newer one.
        syncedRevision = revision
        when {
            // A clear, or a change skipped by reentrancy, needs the full owner list.
            owner == null || !inStep -> syncOwners(parentHandler.externalOwners)
            owner.isDependencyBinding -> Unit
            added -> addOwner(owner)
            else -> removeOwner(owner)
        }
    }

    private fun isPropagated(owner: ViewModelBinding): Boolean =
        propagatedOwners[owner.id] === owner

    private fun addOwner(owner: ViewModelBinding) {
        if (owner.id in propagatedOwners) return
        propagatedOwners[owner.id] = owner
        attachToDependencies(owner)
    }

    private fun removeOwner(owner: ViewModelBinding) {
        if (propagatedOwners.remove(owner.id) == null) return
        detachFromDependencies(owner)
    }

    private fun syncOwners(currentOwners: List<ViewModelBinding>) {
        val current = identitySet<ViewModelBinding>().apply { addAll(currentOwners) }
        val removed = propagatedOwners.values.filter { it !in current }
        val added = currentOwners.filterNot(::isPropagated)

        // Commit the projection before child hooks can reenter owner synchronization.
        propagatedOwners.clear()
        currentOwners.forEach { propagatedOwners[it.id] = it }
        removed.forEach(::detachFromDependencies)
        added.forEach(::attachToDependencies)
    }

    private fun attachToDependencies(owner: ViewModelBinding) {
        dependencies.map { it.key to it.value }.forEach { (handle, edge) ->
            attachOwner(handle, edge, owner)
        }
    }

    private fun detachFromDependencies(owner: ViewModelBinding) {
        for ((handle, edge) in dependencies.map { it.key to it.value }) {
            // A child hook may have propagated the same owner again.
            if (isPropagated(owner)) break
            detachOwner(handle, edge, owner)
        }
    }

    private fun attachOwner(
        handle: InstanceHandle<*>,
        edge: DependencyEdge,
        owner: ViewModelBinding,
    ) {
        if (
            dependencyDisposed || handle.isDisposed || handle.isRecycling ||
            edge.viewModel.isDisposed || owner.isDisposed ||
            dependencies[handle] !== edge || !isPropagated(owner)
        ) return
        handle.bindOwner(owner, source = edge)
    }

    private fun detachOwner(
        handle: InstanceHandle<*>,
        edge: DependencyEdge,
        owner: ViewModelBinding,
    ) {
        handle.unbindOwner(owner, source = edge)
    }
}
