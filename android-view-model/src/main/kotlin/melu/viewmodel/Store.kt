package melu.viewmodel

import java.util.UUID

internal class Store<Value : Any>(
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
        val realKey = factory.arg.key ?: UUID.randomUUID().toString()
        val bindingId = factory.arg.bindingId
        val arg = factory.arg.copy(key = realKey)

        handles[realKey]?.let { cached ->
            if (bindingId != null && !cached.contains(bindingId)) {
                cached.bind(bindingId)
            }
            return cached
        }

        val builder = factory.builder ?: throw ViewModelError("Factory is nil and cache miss.")
        val created = InstanceHandle(
            value = builder(),
            arg = arg,
            index = nextIndex,
            factory = builder,
        )
        nextIndex += 1
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

    fun dispose() {
        if (disposed) return
        disposed = true
        val snapshot = handles.values.toList()
        snapshot.forEach { it.unbindAll(force = true) }
        handles.clear()
    }
}
