package milu.viewmodel

import java.util.IdentityHashMap
import kotlin.reflect.KClass

internal class AutoDisposeInstanceController(
    private val binding: ViewModelBinding,
    private val onRecreate: () -> Unit,
    private val onInstanceAttached: ((InstanceHandle<*>, ViewModel) -> Unit)? = null,
    private val onInstanceDetached: ((InstanceHandle<*>, ViewModel) -> Unit)? = null,
    private val onInstanceRecreated: ((InstanceHandle<*>, ViewModel, ViewModel) -> Unit)? = null,
) {
    private val trackedHandles = IdentityHashMap<InstanceHandle<*>, InstanceHandle<*>>()
    private val listenerDisposers = IdentityHashMap<InstanceHandle<*>, () -> Unit>()
    private val trackedViewModels = IdentityHashMap<InstanceHandle<*>, ViewModel>()
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

    fun <Value : Any> unbind(value: Value) {
        val handle = trackedHandles.keys.firstOrNull { candidate ->
            @Suppress("UNCHECKED_CAST")
            (candidate as InstanceHandle<Value>).value === value
        } ?: return
        detachViewModelRef(handle)
        detachHandle(handle)
        @Suppress("UNCHECKED_CAST")
        (handle as InstanceHandle<Value>).unbind(binding.id)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        trackedHandles.keys.toList().forEach { handle ->
            try {
                detachViewModelRef(handle)
                listenerDisposers.remove(handle)?.invoke()
                if (!handle.isDisposed) handle.unbind(binding.id)
            } catch (error: Throwable) {
                reportViewModelError(
                    error,
                    ErrorType.Dispose,
                    "AutoDisposeInstanceController dispose error",
                )
            }
        }
        trackedHandles.clear()
        listenerDisposers.clear()
        trackedViewModels.clear()
    }

    private fun detachHandle(handle: InstanceHandle<*>) {
        listenerDisposers.remove(handle)?.invoke()
        trackedHandles.remove(handle)
        trackedViewModels.remove(handle)
    }

    private fun detachViewModelRef(handle: InstanceHandle<*>) {
        val viewModel = trackedViewModels[handle] ?: (handle.value as? ViewModel) ?: return
        if (!viewModel.isDisposed) viewModel.refHandler.removeRef(binding)
        onInstanceDetached?.invoke(handle, viewModel)
    }

    private fun <Value : Any> attachRecreateListener(handle: InstanceHandle<Value>) {
        if (disposed || listenerDisposers.containsKey(handle)) return
        val tracked = handle.value as? ViewModel
        if (tracked != null) {
            try {
                onInstanceAttached?.invoke(handle, tracked)
            } catch (error: Throwable) {
                if (!tracked.isDisposed) tracked.refHandler.removeRef(binding)
                if (!handle.isDisposed) handle.unbind(binding.id)
                throw error
            }
            trackedViewModels[handle] = tracked
        }
        trackedHandles[handle] = handle
        listenerDisposers[handle] = handle.addListener { current ->
            try {
                when (current.currentAction) {
                    InstanceAction.Dispose -> {
                        detachViewModelRef(current)
                        detachHandle(current)
                        if (!InstanceManager.isResetting) onRecreate()
                    }
                    InstanceAction.Recreate -> {
                        val previous = trackedViewModels[current]
                        val replacement = current.value as? ViewModel
                        if (replacement != null) {
                            replacement.refHandler.addRef(binding)
                            trackedViewModels[current] = replacement
                            if (previous != null && previous !== replacement) {
                                onInstanceRecreated?.invoke(current, previous, replacement)
                            }
                        }
                        if (!InstanceManager.isResetting) onRecreate()
                    }
                    null -> Unit
                }
            } catch (error: Throwable) {
                reportViewModelError(
                    error,
                    ErrorType.Listener,
                    "AutoDisposeInstanceController recreate listener error",
                )
            }
        }
    }
}
