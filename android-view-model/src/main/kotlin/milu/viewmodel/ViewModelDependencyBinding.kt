package milu.viewmodel

import java.util.IdentityHashMap

/** Stable dependency/lifecycle scope owned by one parent ViewModel generation. */
internal class ViewModelDependencyBinding(
    private val parent: ViewModel,
    parentHandler: ViewModelBindingHandler,
) : ViewModelBinding() {
    override val isDependencyBinding: Boolean = true

    private val propagatedOwners = mutableListOf<ViewModelBinding>()
    // Each attachment gets a token so old cleanup cannot release a replacement edge.
    private class DependencyEdge(val viewModel: ViewModel)
    private val dependencies = IdentityHashMap<InstanceHandle<*>, DependencyEdge>()
    private val removeOwnerListener: () -> Unit
    private var dependencyDisposed = false

    init {
        registerViewModelConstructionRollback(::dispose)
        propagatedOwners += parentHandler.constructionExternalOwners
        removeOwnerListener = parentHandler.addOwnerChangeListener { _, _, _ ->
            syncOwners(parentHandler.externalOwners)
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
            propagatedOwners.toList().forEach { owner -> attachOwner(handle, edge, owner) }
        }
    }

    override fun handleInstanceDetached(
        handle: InstanceHandle<*>,
        viewModel: ViewModel,
    ): () -> Unit {
        val edge = dependencies.remove(handle)
        val owners = propagatedOwners.toList()
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

    private fun syncOwners(currentOwners: List<ViewModelBinding>) {
        if (dependencyDisposed) return
        val removed = propagatedOwners.filter { owner -> currentOwners.none { it === owner } }
        val added = currentOwners.filter { owner -> propagatedOwners.none { it === owner } }

        // Commit the projection before child hooks can reenter owner synchronization.
        propagatedOwners.clear()
        propagatedOwners += currentOwners
        removed.forEach { owner ->
            for ((handle, edge) in dependencies.map { it.key to it.value }) {
                if (propagatedOwners.any { it === owner }) break
                detachOwner(handle, edge, owner)
            }
        }
        added.forEach { owner ->
            dependencies.map { it.key to it.value }.forEach { (handle, edge) ->
                attachOwner(handle, edge, owner)
            }
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
            dependencies[handle] !== edge || propagatedOwners.none { it === owner }
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
