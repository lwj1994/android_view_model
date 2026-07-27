package milu.viewmodel

import java.util.UUID

internal enum class InstanceAction {
    Dispose,
    Recreate,
}

internal class InstanceHandle<Value : Any>(
    value: Value,
    val arg: InstanceArg,
    val index: Int,
    val factory: () -> Value,
) {
    var value: Value? = value
        private set

    private val bindingSources = linkedMapOf<String, MutableSet<Any>>()
    private val directBindingSources = mutableMapOf<String, Any>()
    private val listeners = linkedMapOf<String, (InstanceHandle<Value>) -> Unit>()
    private var disposed = false
    private var action: InstanceAction? = null
    private var lastAction: InstanceAction? = null

    val isDisposed: Boolean
        get() = disposed

    val currentAction: InstanceAction?
        get() = action ?: if (disposed) lastAction else null

    val bindingIds: List<String>
        get() = bindingSources.keys.toList()

    init {
        notifyCreate()
        bind(arg.bindingId)
    }

    fun requireInstance(): Value = value ?: throw ViewModelError(
        "Cannot access ${arg.key} instance after disposal.",
    )

    fun contains(bindingId: String): Boolean = bindingSources.containsKey(bindingId)

    fun bind(id: String?) {
        if (id == null || disposed) return
        val source = directBindingSources.getOrPut(id) { Any() }
        bindFrom(id, source)
    }

    fun bindFrom(
        id: String?,
        source: Any,
    ) {
        if (id == null || disposed) return
        val sources = bindingSources.getOrPut(id) { identitySet() }
        if (!sources.add(source)) return
        if (sources.size == 1) notifyBind(id)
    }

    fun unbind(id: String) {
        val source = directBindingSources.remove(id) ?: return
        unbindFrom(id, source)
    }

    fun unbindFrom(
        id: String,
        source: Any,
    ) {
        if (disposed) return
        val sources = bindingSources[id] ?: return
        if (!sources.remove(source)) return
        if (sources.isNotEmpty()) return
        bindingSources.remove(id)
        notifyUnbind(id)
        if (bindingSources.isEmpty()) {
            recycle()
        }
    }

    fun unbindAll(force: Boolean = false) {
        if (disposed) return
        if (arg.aliveForever && !force) return
        val snapshot = bindingSources.keys.toList()
        bindingSources.clear()
        directBindingSources.clear()
        snapshot.forEach(::notifyUnbind)
        recycle(force = force)
    }

    fun recreate(builder: (() -> Value)? = null): Value {
        if (disposed) {
            throw ViewModelError("Cannot recreate disposed instance.")
        }
        val previous = requireInstance()
        val activeBindingIds = bindingSources.keys.toList()
        val key = requireNotNull(arg.key)
        val recreated = runInViewModelConstruction(
            type = previous::class,
            key = key,
            isImplicit = key is ViewModelPrivateKey,
            block = builder ?: factory,
        )
        if (!isActiveWith(previous)) abortInvalidatedRecreate(previous, recreated)
        callInstanceDispose(previous)
        if (!isActiveWith(previous)) abortInvalidatedRecreate(previous, recreated)
        value = recreated
        notifyCreate()
        requireActiveRecreatedInstance(recreated)
        activeBindingIds.forEach { bindingId ->
            notifyBind(bindingId)
            requireActiveRecreatedInstance(recreated)
        }
        action = InstanceAction.Recreate
        lastAction = InstanceAction.Recreate
        runInViewModelUpdateTransaction(::notifyListeners)
        action = null
        return recreated
    }

    private fun isActiveWith(expected: Value): Boolean = !disposed && value === expected

    private fun abortInvalidatedRecreate(
        previous: Value,
        recreated: Value,
    ): Nothing {
        val replacementIsManaged = isActiveWith(recreated)
        if (!replacementIsManaged && recreated !== previous) {
            callInstanceDispose(recreated)
        }
        throw ViewModelError(
            "Cannot recreate because its handle was disposed or replaced while the builder " +
                "was running. The detached replacement was disposed and was not installed.",
        )
    }

    private fun requireActiveRecreatedInstance(recreated: Value) {
        if (isActiveWith(recreated)) return
        throw ViewModelError(
            "Cannot recreate because its handle was disposed or replaced while the " +
                "replacement lifecycle was being initialized.",
        )
    }

    fun addListener(listener: (InstanceHandle<Value>) -> Unit): () -> Unit {
        val id = UUID.randomUUID().toString()
        listeners[id] = listener
        return { listeners.remove(id) }
    }

    private fun recycle(force: Boolean = false) {
        if (arg.aliveForever && !force) return
        action = InstanceAction.Dispose
        lastAction = InstanceAction.Dispose
        runInViewModelUpdateTransaction(::notifyListeners)
        action = null
        onDispose()
    }

    private fun onDispose() {
        if (disposed) return
        disposed = true
        callInstanceDispose(value)
        value = null
        bindingSources.clear()
        directBindingSources.clear()
        listeners.clear()
    }

    private fun notifyCreate() {
        val lifecycle = value as? InstanceLifeCycle ?: return
        try {
            lifecycle.onCreate(arg)
        } catch (error: Throwable) {
            reportViewModelError(error, ErrorType.Lifecycle, "${value!!::class.qualifiedName} onCreate error")
        }
    }

    private fun notifyBind(id: String) {
        val lifecycle = value as? InstanceLifeCycle ?: return
        try {
            lifecycle.onBind(arg, id)
        } catch (error: Throwable) {
            reportViewModelError(error, ErrorType.Lifecycle, "${value!!::class.qualifiedName} onBind error")
        }
    }

    private fun notifyUnbind(id: String) {
        val lifecycle = value as? InstanceLifeCycle ?: return
        try {
            lifecycle.onUnbind(arg, id)
        } catch (error: Throwable) {
            reportViewModelError(error, ErrorType.Lifecycle, "${value!!::class.qualifiedName} onUnbind error")
        }
    }

    private fun callInstanceDispose(target: Value?) {
        val lifecycle = target as? InstanceLifeCycle ?: return
        try {
            lifecycle.onDispose(arg)
        } catch (error: Throwable) {
            reportViewModelError(error, ErrorType.Dispose, "${target::class.qualifiedName} onDispose error")
        }
    }

    private fun notifyListeners() {
        listeners.values.toList().forEach { listener ->
            listener(this)
        }
    }
}
