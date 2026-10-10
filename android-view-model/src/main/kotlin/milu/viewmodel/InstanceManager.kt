package milu.viewmodel

import android.util.Log
import androidx.annotation.MainThread
import java.util.IdentityHashMap
import kotlin.reflect.KClass

@MainThread
public object InstanceManager {
    private val stores = linkedMapOf<KClass<*>, Store<*>>()
    private val ambiguousTagLookupWarnings =
        mutableMapOf<KClass<*>, IdentityHashMap<Any, Boolean>>()
    internal var isResetting: Boolean = false
        private set

    internal val debugAmbiguousTagLookupWarningCount: Int
        get() = ambiguousTagLookupWarnings.values.sumOf { it.size }

    internal fun <Value : Any> get(
        type: KClass<Value>,
        factory: InstanceFactory<Value>? = null,
    ): Value = getHandle(type, factory).requireInstance()

    internal fun <Value : Any> maybeGet(
        type: KClass<Value>,
        factory: InstanceFactory<Value>? = null,
    ): Value? = try {
        get(type, factory)
    } catch (_: ViewModelError) {
        null
    }

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
            if (BuildConfig.DEBUG && factory?.arg?.tag != null) {
                warnAmbiguousTagLookup(type, store, factory.arg.tag)
            }
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
                if (BuildConfig.DEBUG) {
                    warnAmbiguousTagLookup(type, store, factory.arg.tag)
                }
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
            ambiguousTagLookupWarnings.clear()
            val snapshot = stores.values.toList()
            stores.clear()
            snapshot.forEach { it.dispose() }
        } finally {
            isResetting = false
        }
    }

    private fun <Value : Any> warnAmbiguousTagLookup(
        type: KClass<Value>,
        store: Store<Value>,
        tag: Any,
    ) {
        // Query equality and diagnostic identity are deliberately separate.
        // Never require a user-provided tag to implement a usable hashCode.
        if (ambiguousTagLookupWarnings[type]?.containsKey(tag) == true) return
        val matches = store.countInstances(tag)
        if (matches < 2) return
        ambiguousTagLookupWarnings.getOrPut(type) { IdentityHashMap() }[tag] = true

        // Even Object.toString() can call an overridden hashCode(). Describe
        // the object without invoking either user-defined method.
        val tagDescription = "${tag.javaClass.name}@" +
            Integer.toHexString(System.identityHashCode(tag))
        try {
            Log.w(
                "AndroidViewModel",
                "A single-result cached lookup of ${type.qualifiedName} by tag " +
                    "$tagDescription matched $matches instances and returned the " +
                    "most recently created one. Use readCachesByTag/watchCachesByTag " +
                    "for a shared tag, or an explicit key for one instance.",
            )
        } catch (_: Throwable) {
            // Android logging may be unavailable in local JVM environments.
            // Diagnostics must not change a successful lookup's result.
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
