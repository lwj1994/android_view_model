package milu.viewmodel

import androidx.annotation.MainThread
import kotlin.reflect.KClass

@MainThread
public object InstanceManager {
    private val stores = linkedMapOf<KClass<*>, Store<*>>()
    internal var isResetting: Boolean = false
        private set

    internal fun <Value : Any> get(
        type: KClass<Value>,
        factory: InstanceFactory<Value>? = null,
    ): Value = getHandle(type, factory).requireInstance()

    internal fun <Value : Any> maybeGet(
        type: KClass<Value>,
        factory: InstanceFactory<Value>? = null,
    ): Value? = runCatching { get(type, factory) }.getOrNull()

    internal fun <Value : Any> getHandle(
        type: KClass<Value>,
        factory: InstanceFactory<Value>? = null,
    ): InstanceHandle<Value> {
        assertMainThread()
        requireNotResetting()
        val store = store(type)
        if (factory == null || factory.isEmpty) {
            val found = store.findNewest(tag = factory?.arg?.tag)
                ?: throw ViewModelError("no ${type.qualifiedName} instance found")
            val bindingId = factory?.arg?.bindingId
            if (bindingId != null) {
                val extendFactory = InstanceFactory<Value>(
                    arg = InstanceArg(
                        key = found.arg.key,
                        tag = found.arg.tag,
                        bindingId = bindingId,
                        aliveForever = found.arg.aliveForever,
                    ),
                )
                return store.getHandle(extendFactory)
            }
            return found
        }
        if (factory.builder == null && factory.arg.key != null && factory.arg.tag != null) {
            return try {
                store.getHandle(factory)
            } catch (_: ViewModelError) {
                val found = store.findNewest(tag = factory.arg.tag)
                    ?: throw ViewModelError("no ${type.qualifiedName} instance found")
                val bindingId = factory.arg.bindingId
                if (bindingId != null) {
                    return store.getHandle(
                        InstanceFactory(
                            arg = InstanceArg(
                                key = found.arg.key,
                                tag = found.arg.tag,
                                bindingId = bindingId,
                                aliveForever = found.arg.aliveForever,
                            ),
                        ),
                    )
                }
                found
            }
        }
        return store.getHandle(factory)
    }

    internal fun <Value : Any> getHandlesByTag(
        tag: Any,
        type: KClass<Value>,
    ): List<InstanceHandle<Value>> {
        requireNotResetting()
        return store(type).instancesByTag(tag)
    }

    internal fun <Value : Any> recreate(
        value: Value,
        type: KClass<Value>,
        builder: (() -> Value)? = null,
    ): Value {
        requireNotResetting()
        return store(type).recreate(value, builder)
    }

    internal fun recycle(value: Any) {
        assertMainThread()
        requireNotResetting()
        stores.values.toList().forEach { store ->
            @Suppress("UNCHECKED_CAST")
            if ((store as Store<Any>).tryRecycle(value)) return
        }
        throw ViewModelError(
            "Cannot recycle ${value::class.qualifiedName}. Instance not found in store.",
        )
    }

    public val debugStoreCount: Int
        get() = stores.size

    public fun debugReset() {
        assertMainThread()
        if (isResetting) return
        isResetting = true
        try {
            val snapshot = stores.values.toList()
            stores.clear()
            snapshot.forEach { it.dispose() }
        } finally {
            isResetting = false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <Value : Any> store(type: KClass<Value>): Store<Value> {
        val cached = stores[type]
        if (cached != null) return cached as Store<Value>

        lateinit var created: Store<Value>
        created = Store(type) {
            val current = stores[type]
            if (current === created && created.isEmpty) {
                stores.remove(type)
                created.dispose()
            }
        }
        stores[type] = created
        return created
    }

    private fun requireNotResetting() {
        if (isResetting) {
            throw ViewModelError("Cannot access ViewModels while InstanceManager is resetting.")
        }
    }
}
