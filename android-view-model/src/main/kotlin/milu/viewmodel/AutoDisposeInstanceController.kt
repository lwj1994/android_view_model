package milu.viewmodel

import kotlin.reflect.KClass

internal class AutoDisposeInstanceController(
    private val binding: ViewModelBinding,
    private val onRecreate: () -> Unit,
) {
    private val trackedHandles = linkedMapOf<InstanceHandle<*>, InstanceHandle<*>>()
    private val listenerDisposers = linkedMapOf<InstanceHandle<*>, () -> Unit>()
    private var disposed = false

    fun <Value : Any> getInstance(
        type: KClass<Value>,
        factory: InstanceFactory<Value>,
    ): Value {
        if (disposed) {
            throw ViewModelError("AutoDisposeInstanceController.getInstance() called after dispose.")
        }
        val factoryWithBinding = factory.copy(
            arg = factory.arg.copy(bindingId = binding.id),
        )
        val handle = InstanceManager.getHandle(type, factoryWithBinding)
        (handle.value as? ViewModel)?.refHandler?.addRef(binding)
        attachRecreateListener(handle)
        return handle.requireInstance()
    }

    fun <Value : Any> getInstancesByTag(
        type: KClass<Value>,
        tag: Any,
        observeRecreate: Boolean,
    ): List<Value> {
        val handles = InstanceManager.getHandlesByTag(tag, type)
        val result = mutableListOf<Value>()
        handles.forEach { handle ->
            handle.bind(binding.id)
            (handle.value as? ViewModel)?.refHandler?.addRef(binding)
            if (observeRecreate) {
                attachRecreateListener(handle)
            } else {
                trackedHandles[handle] = handle
            }
            handle.value?.let { result += it }
        }
        return result
    }

    fun <Value : Any> recycle(value: Value) {
        val entry = trackedHandles.keys.firstOrNull { handle ->
            @Suppress("UNCHECKED_CAST")
            (handle as InstanceHandle<Value>).value === value
        } ?: return
        listenerDisposers.remove(entry)?.invoke()
        @Suppress("UNCHECKED_CAST")
        (entry as InstanceHandle<Value>).unbindAll(force = true)
        trackedHandles.remove(entry)
    }

    fun <Value : Any> unbind(value: Value) {
        val handle = trackedHandles.keys.firstOrNull { candidate ->
            @Suppress("UNCHECKED_CAST")
            (candidate as InstanceHandle<Value>).value === value
        } ?: return
        @Suppress("UNCHECKED_CAST")
        (handle as InstanceHandle<Value>).unbind(binding.id)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        listenerDisposers.values.toList().forEach { it() }
        listenerDisposers.clear()
        trackedHandles.keys.toList().forEach { handle ->
            if (handle.isDisposed) return@forEach
            (handle.value as? ViewModel)?.refHandler?.removeRef(binding)
            handle.unbind(binding.id)
        }
        trackedHandles.clear()
    }

    private fun <Value : Any> attachRecreateListener(handle: InstanceHandle<Value>) {
        trackedHandles[handle] = handle
        if (listenerDisposers.containsKey(handle)) return

        val disposer = handle.addListener { current ->
            when (current.currentAction) {
                InstanceAction.Recreate -> {
                    (current.value as? ViewModel)?.refHandler?.addRef(binding)
                    onRecreate()
                }
                InstanceAction.Dispose,
                null,
                -> Unit
            }
        }
        listenerDisposers[handle] = disposer
    }
}
