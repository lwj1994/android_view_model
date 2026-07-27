package milu.viewmodel

import java.util.IdentityHashMap

/** Stable dependency/lifecycle scope owned by one parent ViewModel generation. */
internal class ViewModelDependencyBinding(
    private val parent: ViewModel,
    parentHandler: ViewModelBindingHandler,
    private val onDependencyUpdate: (ViewModel) -> Unit,
) : ViewModelBinding() {
    override val isDependencyBinding: Boolean = true

    private val propagatedOwners = mutableListOf<ViewModelBinding>()
    private val dependencies = IdentityHashMap<InstanceHandle<*>, ViewModel>()
    private val removeOwnerListener: () -> Unit
    private var dependencyDisposed = false

    init {
        registerViewModelConstructionRollback(::dispose)
        propagatedOwners += parentHandler.constructionExternalOwners
        removeOwnerListener = parentHandler.addOwnerChangeListener { _, _, _ ->
            syncOwners(parentHandler.externalOwners)
        }
    }

    override fun handleInstanceAttached(
        handle: InstanceHandle<*>,
        viewModel: ViewModel,
    ) {
        requireAcyclicDependency(viewModel)
        super.handleInstanceAttached(handle, viewModel)
        dependencies[handle] = viewModel
        propagatedOwners.forEach { owner -> attachOwner(handle, viewModel, owner) }
    }

    override fun handleInstanceDetached(
        handle: InstanceHandle<*>,
        viewModel: ViewModel,
    ) {
        dependencies.remove(handle)
        super.handleInstanceDetached(handle, viewModel)
        notifyDependency(viewModel)
    }

    override fun onViewModelUpdate(viewModel: ViewModel) {
        onDependencyUpdate(viewModel)
    }

    override fun onUpdate() {
        // Handle disposal is forwarded by the source-aware hooks.
    }

    override fun dispose() {
        if (dependencyDisposed) return
        dependencyDisposed = true
        removeOwnerListener()
        propagatedOwners.toList().forEach { owner ->
            dependencies.entries.toList().forEach { (handle, viewModel) ->
                detachOwner(handle, viewModel, owner)
            }
        }
        propagatedOwners.clear()
        super.dispose()
        dependencies.clear()
    }

    private fun notifyDependency(viewModel: ViewModel) {
        if (
            dependencyDisposed ||
            InstanceManager.isResetting ||
            !markViewModelBindingUpdated(this)
        ) {
            return
        }
        onDependencyUpdate(viewModel)
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
        return dependencies.values.any { dependency ->
            dependency === target ||
                dependency.dependencyBindingIfCreated?.reaches(target, visited) == true
        }
    }

    private fun syncOwners(currentOwners: List<ViewModelBinding>) {
        if (dependencyDisposed) return
        val removed = propagatedOwners.filter { owner -> currentOwners.none { it === owner } }
        val added = currentOwners.filter { owner -> propagatedOwners.none { it === owner } }

        removed.forEach { owner ->
            dependencies.entries.toList().forEach { (handle, viewModel) ->
                detachOwner(handle, viewModel, owner)
            }
            propagatedOwners.removeAll { it === owner }
        }
        added.forEach { owner ->
            propagatedOwners += owner
            dependencies.entries.toList().forEach { (handle, viewModel) ->
                attachOwner(handle, viewModel, owner)
            }
        }
    }

    private fun attachOwner(
        handle: InstanceHandle<*>,
        viewModel: ViewModel,
        owner: ViewModelBinding,
    ) {
        if (handle.isDisposed || viewModel.isDisposed) return
        handle.bindFrom(owner.id, this)
        viewModel.refHandler.addRef(owner, source = this)
    }

    private fun detachOwner(
        handle: InstanceHandle<*>,
        viewModel: ViewModel,
        owner: ViewModelBinding,
    ) {
        if (!viewModel.isDisposed) {
            viewModel.refHandler.removeRef(owner, source = this)
        }
        if (!handle.isDisposed) {
            handle.unbindFrom(owner.id, this)
        }
    }
}
