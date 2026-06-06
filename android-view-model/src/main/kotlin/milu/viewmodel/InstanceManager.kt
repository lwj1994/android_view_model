package milu.viewmodel

import androidx.annotation.MainThread
import kotlin.reflect.KClass

@MainThread
public object InstanceManager {
    private val stores = linkedMapOf<KClass<*>, Store<Any>>()

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
    ): List<InstanceHandle<Value>> = store(type).instancesByTag(tag)

    internal fun <Value : Any> recreate(
        value: Value,
        type: KClass<Value>,
        builder: (() -> Value)? = null,
    ): Value = store(type).recreate(value, builder)

    public val debugStoreCount: Int
        get() = stores.size

    public fun debugReset() {
        assertMainThread()
        val snapshot = stores.values.toList()
        stores.clear()
        snapshot.forEach { it.dispose() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <Value : Any> store(type: KClass<Value>): Store<Value> {
        val cached = stores[type]
        if (cached != null) return cached as Store<Value>

        lateinit var created: Store<Any>
        created = Store {
            val current = stores[type]
            if (current === created && created.isEmpty) {
                stores.remove(type)
                created.dispose()
            }
        }
        stores[type] = created
        return created as Store<Value>
    }
}
