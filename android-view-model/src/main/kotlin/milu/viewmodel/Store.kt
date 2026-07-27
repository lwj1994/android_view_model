package milu.viewmodel

import kotlin.reflect.KClass

internal class Store<Value : Any>(
    private val type: KClass<Value>,
    private val onStoreEmpty: (() -> Unit)? = null,
) {
    private val handles = linkedMapOf<Any, InstanceHandle<Value>>()
    private var nextIndex = 0
    private var disposed = false

    val isEmpty: Boolean
        get() = handles.isEmpty()

    fun instancesByTag(tag: Any): List<InstanceHandle<Value>> {
        if (disposed) return emptyList()
        return handles.values
            .filter { it.arg.tag == tag }
            .sortedByDescending { it.index }
    }

    fun findNewest(tag: Any? = null): InstanceHandle<Value>? {
        if (disposed) {
            throw ViewModelError("Store has been disposed.")
        }
        if (tag != null) return instancesByTag(tag).firstOrNull()
        return handles.values.maxByOrNull { it.index }
    }

    fun getHandle(factory: InstanceFactory<Value>): InstanceHandle<Value> {
        if (disposed) {
            throw ViewModelError("Store has been disposed.")
        }
        val realKey = factory.arg.key ?: ViewModelPrivateKey()
        val bindingId = factory.arg.bindingId
        val arg = factory.arg.copy(key = realKey)

        handles[realKey]?.let { cached ->
            // Direct and parent-propagated ownership may expose the same id.
            // bind() is source-aware, so the direct path must still be recorded.
            cached.bind(bindingId)
            return cached
        }

        val builder = factory.builder ?: throw ViewModelError("Factory is nil and cache miss.")
        val created = runInViewModelConstruction(
            type = type,
            key = realKey,
            isImplicit = realKey is ViewModelPrivateKey,
        ) {
            val instance = builder()
            if (disposed) {
                disposeUntrackedInstance(instance, arg)
                throw ViewModelError(
                    "Cannot create ${type.qualifiedName} because its Store was disposed " +
                        "while the factory builder was running. The new instance was " +
                        "disposed and was not cached.",
                )
            }
            InstanceHandle(
                value = instance,
                arg = arg,
                index = nextIndex++,
                factory = builder,
            )
        }
        if (disposed) {
            created.unbindAll(force = true)
            throw ViewModelError(
                "Cannot create ${type.qualifiedName} because its Store was disposed " +
                    "during instance creation. The new instance was disposed and was not cached.",
            )
        }
        handles[realKey] = created

        created.addListener { handle ->
            if (handle.currentAction != InstanceAction.Dispose) return@addListener
            handles.remove(realKey)
            if (handles.isEmpty()) {
                onStoreEmpty?.invoke()
            }
        }
        return created
    }

    fun recreate(
        target: Value,
        builder: (() -> Value)? = null,
    ): Value {
        if (disposed) {
            throw ViewModelError("Store has been disposed.")
        }
        val handle = handles.values.firstOrNull { it.value === target }
            ?: throw ViewModelError("Cannot recreate instance. Instance not found in store.")
        return handle.recreate(builder)
    }

    fun tryRecycle(instance: Any): Boolean {
        if (disposed) return false
        val handle = handles.values.firstOrNull { it.value === instance } ?: return false
        handle.unbindAll(force = true)
        return true
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        val snapshot = handles.values.toList()
        snapshot.forEach { it.unbindAll(force = true) }
        handles.clear()
    }

    private fun disposeUntrackedInstance(
        instance: Value,
        arg: InstanceArg,
    ) {
        val lifecycle = instance as? InstanceLifeCycle ?: return
        try {
            lifecycle.onDispose(arg)
        } catch (error: Throwable) {
            reportViewModelError(
                error,
                ErrorType.Dispose,
                "Untracked ${type.qualifiedName} instance dispose error",
            )
        }
    }
}
