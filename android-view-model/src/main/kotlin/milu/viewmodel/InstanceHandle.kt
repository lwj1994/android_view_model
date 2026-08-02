package milu.viewmodel

import java.util.UUID

internal class InstanceHandle<Value : Any>(
    value: Value,
    val arg: InstanceArg,
    val index: Int,
) {
    var value: Value? = value
        private set

    private val bindingSources = linkedMapOf<String, MutableSet<Any>>()
    private val directBindingSources = mutableMapOf<String, Any>()
    private val listeners = linkedMapOf<String, (InstanceHandle<Value>) -> Unit>()
    private var disposed = false
    val isDisposed: Boolean
        get() = disposed

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

    fun addListener(listener: (InstanceHandle<Value>) -> Unit): () -> Unit {
        val id = UUID.randomUUID().toString()
        listeners[id] = listener
        return { listeners.remove(id) }
    }

    private fun recycle(force: Boolean = false) {
        if (arg.aliveForever && !force) return
        runInViewModelUpdateTransaction(::notifyListeners)
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
        listeners.toList().forEach { (id, listener) ->
            if (listeners[id] !== listener) return@forEach
            listener(this)
        }
    }
}
