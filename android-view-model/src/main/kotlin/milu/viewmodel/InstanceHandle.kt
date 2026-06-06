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

    private val bindingIds = linkedSetOf<String>()
    private val listeners = linkedMapOf<String, (InstanceHandle<Value>) -> Unit>()
    private var disposed = false
    private var action: InstanceAction? = null
    private var lastAction: InstanceAction? = null

    val isDisposed: Boolean
        get() = disposed

    val currentAction: InstanceAction?
        get() = action ?: if (disposed) lastAction else null

    init {
        notifyCreate()
        bind(arg.bindingId)
    }

    fun requireInstance(): Value = value ?: throw ViewModelError(
        "Cannot access ${arg.key} instance after disposal.",
    )

    fun contains(bindingId: String): Boolean = bindingIds.contains(bindingId)

    fun bind(id: String?) {
        if (id == null || disposed || bindingIds.contains(id)) return
        bindingIds += id
        notifyBind(id)
    }

    fun unbind(id: String) {
        if (disposed || !bindingIds.remove(id)) return
        notifyUnbind(id)
        if (bindingIds.isEmpty()) {
            recycle()
        }
    }

    fun unbindAll(force: Boolean = false) {
        if (disposed) return
        if (arg.aliveForever && !force) return
        val snapshot = bindingIds.toList()
        bindingIds.clear()
        snapshot.forEach(::notifyUnbind)
        recycle(force = force)
    }

    fun recreate(builder: (() -> Value)? = null): Value {
        if (disposed) {
            throw ViewModelError("Cannot recreate disposed instance.")
        }
        val previous = requireInstance()
        val activeBindingIds = bindingIds.toList()
        val recreated = (builder ?: factory)()
        callInstanceDispose(previous)
        value = recreated
        notifyCreate()
        activeBindingIds.forEach(::notifyBind)
        action = InstanceAction.Recreate
        lastAction = InstanceAction.Recreate
        notifyListeners()
        action = null
        return recreated
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
        notifyListeners()
        action = null
        onDispose()
    }

    private fun onDispose() {
        if (disposed) return
        disposed = true
        callInstanceDispose(value)
        value = null
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
