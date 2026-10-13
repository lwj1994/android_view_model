package milu.viewmodel

import java.util.IdentityHashMap

internal class AutoDisposeInstanceController(
    private val binding: ViewModelBinding,
    private val onHandleDisposing: () -> Unit,
    private val onInstanceAttaching: ((InstanceHandle<*>, ViewModel) -> Unit)? = null,
    private val onInstanceAttached: ((InstanceHandle<*>, ViewModel) -> (() -> Unit)?)? = null,
    private val onInstanceDetached: ((InstanceHandle<*>, ViewModel) -> (() -> Unit)?)? = null,
) {
    private val listenerDisposers = IdentityHashMap<InstanceHandle<*>, () -> Unit>()
    private var disposed = false

    fun <Value : Any> getInstance(
        type: InstanceType<Value>,
        factory: InstanceFactory<Value>,
    ): Value = getHandle(type, factory).requireInstance()

    /** Cache hits and new generations use the same validated ownership commit. */
    fun <Value : Any> getHandle(
        type: InstanceType<Value>,
        factory: InstanceFactory<Value>,
    ): InstanceHandle<Value> {
        requireLiveBinding()
        val factoryWithBinding = factory.copy(arg = factory.arg.copy(bindingId = binding.id))
        return InstanceManager.getHandle(type, factoryWithBinding) { attachHandle(it) }
    }

    fun <Value : Any> getInstancesByTag(
        type: InstanceType<Value>,
        tag: Any,
    ): List<Value> {
        requireLiveBinding()
        val handles = InstanceManager.getHandlesByTag(tag, type)
        // Reject invalid edges before any member can publish lifecycle or owner events.
        handles.forEach(::validateAttachment)
        val added = mutableListOf<Pair<InstanceHandle<*>, () -> Unit>>()
        try {
            return handles.map { handle ->
                val registration = attachHandle(handle)
                if (registration != null) added += handle to registration
                handle.requireInstance()
            }
        } catch (error: Throwable) {
            added.asReversed().forEach { (handle, registration) ->
                if (listenerDisposers[handle] === registration) detachHandle(handle)
            }
            throw error
        }
    }

    fun <Value : Any> unbind(value: Value) {
        val handle = listenerDisposers.keys.firstOrNull { it.value === value } ?: return
        detachHandle(handle)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        listenerDisposers.keys.toList().forEach { handle ->
            try {
                detachHandle(handle)
            } catch (error: Throwable) {
                reportViewModelError(error, ErrorType.Dispose, "AutoDisposeInstanceController dispose error")
            }
        }
        listenerDisposers.clear()
    }

    private fun requireLiveBinding() {
        if (disposed || binding.isDisposed) {
            throw ViewModelError("Cannot resolve an instance for a disposed binding.")
        }
    }

    private fun requireLive(handle: InstanceHandle<*>) {
        requireLiveBinding()
        if (handle.isDisposed || handle.isRecycling) {
            throw ViewModelError("Cannot resolve an instance while it is being recycled or disposed.")
        }
    }

    private fun validateAttachment(handle: InstanceHandle<*>) {
        requireLive(handle)
        if (!listenerDisposers.containsKey(handle)) {
            (handle.value as? ViewModel)?.let { onInstanceAttaching?.invoke(handle, it) }
        }
    }

    private fun detachHandle(handle: InstanceHandle<*>) {
        // Remove this registration and its subscriptions before callbacks can replace it.
        val unregister = listenerDisposers.remove(handle) ?: return
        unregister()
        val publish = (handle.value as? ViewModel)?.let { onInstanceDetached?.invoke(handle, it) }
        handle.unbindOwner(binding)
        publish?.invoke()
    }

    /** Returns only this attempt's registration, never one installed by reentrant code. */
    private fun <Value : Any> attachHandle(handle: InstanceHandle<Value>): (() -> Unit)? {
        validateAttachment(handle)
        if (listenerDisposers.containsKey(handle)) return null
        val publish = (handle.value as? ViewModel)?.let { onInstanceAttached?.invoke(handle, it) }
        val registration = handle.addListener { current ->
            try {
                detachHandle(current)
                if (!InstanceManager.isResetting && !disposed && !binding.isDisposed) onHandleDisposing()
            } catch (error: Throwable) {
                reportViewModelError(error, ErrorType.Listener, "AutoDisposeInstanceController handle listener error")
            }
        }
        listenerDisposers[handle] = registration
        try {
            // Adjacency and disposal tracking are ready before the first onBind callback.
            handle.bindOwner(binding)
            if (listenerDisposers[handle] === registration) publish?.invoke()
            requireLive(handle)
            if (!handle.containsDirectBinding(binding.id)) {
                throw ViewModelError("Ownership was released while binding an instance.")
            }
            return registration
        } catch (error: Throwable) {
            if (listenerDisposers[handle] === registration) detachHandle(handle)
            throw error
        }
    }
}
