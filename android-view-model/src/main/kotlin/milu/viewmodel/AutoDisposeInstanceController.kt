package milu.viewmodel

import java.util.IdentityHashMap

internal class AutoDisposeInstanceController(
    private val binding: ViewModelBinding,
    private val onHandleDisposing: () -> Unit,
    private val onInstanceAttached: ((InstanceHandle<*>, ViewModel) -> Unit)? = null,
    private val onInstanceDetached: ((InstanceHandle<*>, ViewModel) -> Unit)? = null,
    private val onInstanceDetachmentComplete: ((InstanceHandle<*>, ViewModel) -> Unit)? = null,
) {
    private val trackedHandles = IdentityHashMap<InstanceHandle<*>, InstanceHandle<*>>()
    private val listenerDisposers = IdentityHashMap<InstanceHandle<*>, () -> Unit>()
    private var disposed = false
    var isUnbinding: Boolean = false
        private set

    fun <Value : Any> getInstance(
        type: InstanceType<Value>,
        factory: InstanceFactory<Value>,
    ): Value = getHandle(type, factory).requireInstance()

    /** Resolves and tracks the handle so bindings can reuse their direct ownership. */
    fun <Value : Any> getHandle(
        type: InstanceType<Value>,
        factory: InstanceFactory<Value>,
    ): InstanceHandle<Value> {
        if (disposed || binding.isDisposed) {
            throw ViewModelError("AutoDisposeInstanceController.getInstance() called after dispose.")
        }
        requireNotUnbinding()
        val factoryWithBinding = factory.copy(
            arg = factory.arg.copy(bindingId = binding.id),
        )
        val handle = InstanceManager.getHandle(type, factoryWithBinding)
        (handle.value as? ViewModel)?.refHandler?.addRef(binding)
        attachHandleListener(handle)
        return handle
    }

    fun <Value : Any> getInstancesByTag(
        type: InstanceType<Value>,
        tag: Any,
    ): List<Value> {
        // The binding marks itself disposed before running user teardown callbacks.
        // Reject reentrant lookups as well as calls after this controller is disposed,
        // before any handle is bound or a ViewModel owner reference is added.
        if (disposed || binding.isDisposed) {
            throw ViewModelError("AutoDisposeInstanceController.getInstancesByTag() called after dispose.")
        }
        requireNotUnbinding()
        val handles = InstanceManager.getHandlesByTag(tag, type)
        val result = mutableListOf<Value>()
        handles.forEach { handle ->
            handle.bind(binding.id)
            (handle.value as? ViewModel)?.refHandler?.addRef(binding)
            attachHandleListener(handle)
            handle.value?.let { result += it }
        }
        return result
    }

    fun <Value : Any> unbind(value: Value) {
        requireNotUnbinding()
        val handle = trackedHandles.keys.firstOrNull { candidate ->
            @Suppress("UNCHECKED_CAST")
            (candidate as InstanceHandle<Value>).value === value
        } ?: return
        val viewModel: ViewModel?
        isUnbinding = true
        try {
            detachHandle(handle)
            viewModel = detachViewModelRef(handle)
            @Suppress("UNCHECKED_CAST")
            (handle as InstanceHandle<Value>).unbind(binding.id)
        } finally {
            isUnbinding = false
        }
        if (viewModel != null) onInstanceDetachmentComplete?.invoke(handle, viewModel)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        trackedHandles.keys.toList().forEach { handle ->
            try {
                detachHandle(handle)
                val viewModel = detachViewModelRef(handle)
                if (!handle.isDisposed) handle.unbind(binding.id)
                if (viewModel != null) onInstanceDetachmentComplete?.invoke(handle, viewModel)
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
    }

    private fun detachHandle(handle: InstanceHandle<*>) {
        listenerDisposers.remove(handle)?.invoke()
        trackedHandles.remove(handle)
    }

    private fun requireNotUnbinding() {
        if (isUnbinding) {
            throw ViewModelError("Cannot resolve or unbind ViewModels while this binding is unbinding.")
        }
    }

    private fun detachViewModelRef(handle: InstanceHandle<*>): ViewModel? {
        val viewModel = handle.value as? ViewModel ?: return null
        if (!viewModel.isDisposed) viewModel.refHandler.removeRef(binding)
        onInstanceDetached?.invoke(handle, viewModel)
        return viewModel
    }

    private fun <Value : Any> attachHandleListener(handle: InstanceHandle<Value>) {
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
        }
        trackedHandles[handle] = handle
        listenerDisposers[handle] = handle.addListener { current ->
            try {
                detachHandle(current)
                val viewModel = detachViewModelRef(current)
                if (viewModel != null) onInstanceDetachmentComplete?.invoke(current, viewModel)
                if (!InstanceManager.isResetting) onHandleDisposing()
            } catch (error: Throwable) {
                reportViewModelError(
                    error,
                    ErrorType.Listener,
                    "AutoDisposeInstanceController handle listener error",
                )
            }
        }
    }
}
