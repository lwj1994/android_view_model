package milu.viewmodel

import java.util.UUID

internal class InstanceHandle<Value : Any>(
    value: Value,
    val arg: InstanceArg,
    val index: Int,
    bindOnCreate: Boolean = true,
) {
    var value: Value? = value
        private set

    private val bindingSources = linkedMapOf<String, MutableSet<Any>>()
    private val directBindingSources = mutableMapOf<String, Any>()
    private val pendingUnbinds = linkedMapOf<String, Any>()
    private val listeners = linkedMapOf<String, (InstanceHandle<Value>) -> Unit>()
    private var disposed = false
    private var pendingOwnerChange: (() -> Unit)? = null
    val isDisposed: Boolean
        get() = disposed

    var isRecycling: Boolean = false
        private set

    val bindingIds: List<String>
        get() = bindingSources.keys.toList()

    init {
        notifyCreate()
        if (bindOnCreate) bind(arg.bindingId)
    }

    fun requireInstance(): Value = value ?: throw ViewModelError(
        "Cannot access ${arg.key} instance after disposal.",
    )

    fun contains(bindingId: String): Boolean = bindingSources.containsKey(bindingId)

    /** Direct ownership is independent of any parent-propagated source for the same id. */
    fun containsDirectBinding(bindingId: String): Boolean =
        directBindingSources.containsKey(bindingId)

    fun bind(id: String?) {
        if (id == null || disposed || isRecycling) return
        val source = directBindingSources.getOrPut(id) { Any() }
        bindFrom(id, source)
    }

    fun bindFrom(
        id: String?,
        source: Any,
    ) {
        if (id == null || disposed || isRecycling) return
        val sources = bindingSources.getOrPut(id) { identitySet() }
        if (!sources.add(source)) return
        pendingUnbinds.remove(id)
        if (sources.size == 1) notifyBind(id)
    }

    fun unbind(id: String) {
        val source = directBindingSources.remove(id) ?: return
        unbindFrom(id, source)
    }

    /** Commits both ownership views before lifecycle hooks or owner listeners run. */
    fun bindOwner(owner: ViewModelBinding, source: Any? = null) {
        if (disposed || isRecycling || owner.isDisposed) {
            throw ViewModelError("Cannot bind a disposed owner or instance.")
        }
        val token = source ?: directBindingSources.getOrPut(owner.id) { Any() }
        val sources = bindingSources.getOrPut(owner.id) { identitySet() }
        val first = sources.add(token) && sources.size == 1
        pendingUnbinds.remove(owner.id)
        val publish = (value as? ViewModel)?.refHandler?.addRef(
            owner, source = source ?: owner, notify = false,
        )
        if (first) notifyBind(owner.id)
        publish?.invoke()
    }

    /** Releases one source without removing independent or reentrant ownership. */
    fun unbindOwner(owner: ViewModelBinding, source: Any? = null) {
        if (disposed) return
        val token = source ?: directBindingSources.remove(owner.id)
        val sources = bindingSources[owner.id]
        val removed = token != null && sources?.remove(token) == true
        val last = removed && sources!!.isEmpty()
        if (last) bindingSources.remove(owner.id)
        val pendingUnbind = if (last) Any() else null
        if (pendingUnbind != null) pendingUnbinds[owner.id] = pendingUnbind
        val publish = (value as? ViewModel)?.refHandler?.removeRef(
            owner, source = source ?: owner, notify = false,
        )
        publish?.invoke()
        publishPendingOwnerChange()
        // 传播回调可能重绑后再次解绑；旧释放不得重复派发新一轮的 onUnbind。
        if (pendingUnbind != null && pendingUnbinds[owner.id] === pendingUnbind) {
            pendingUnbinds.remove(owner.id)
            notifyUnbind(owner.id)
        }
        if (removed && bindingSources.isEmpty()) recycle()
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
        if (disposed || isRecycling) return
        if (arg.aliveForever && !force) return
        isRecycling = true
        val snapshot = bindingSources.keys.toList()
        bindingSources.clear()
        directBindingSources.clear()
        pendingOwnerChange = (value as? ViewModel)?.refHandler?.clearRefs(notify = false)
        snapshot.forEach(::notifyUnbind)
        finishRecycle()
    }

    fun addListener(listener: (InstanceHandle<Value>) -> Unit): () -> Unit {
        val id = UUID.randomUUID().toString()
        listeners[id] = listener
        return { listeners.remove(id) }
    }

    private fun recycle(force: Boolean = false) {
        if (arg.aliveForever && !force) return
        if (disposed || isRecycling) return
        // 普通解绑仍允许 onUnbind 重获 owner，派发后再决定是否销毁。
        publishPendingUnbinds()
        if (disposed || isRecycling || bindingSources.isNotEmpty()) return
        // Store removal and disposal callbacks run before value is cleared. A binding must
        // not serve this generation from its resolution cache during those callbacks.
        isRecycling = true
        finishRecycle()
    }

    private fun publishPendingOwnerChange() {
        val publish = pendingOwnerChange
        pendingOwnerChange = null
        publish?.invoke()
    }

    private fun finishRecycle() {
        try {
            // 传播中提前 recycle/reset 时，在 Store 移除和销毁前补齐最终解绑。
            publishPendingUnbinds()
            runInViewModelUpdateTransaction {
                // The Store listener removes this generation before owner propagation
                // and controller refreshes can resolve a replacement generation.
                notifyListeners()
                publishPendingOwnerChange()
            }
        } finally {
            onDispose()
        }
    }

    private fun publishPendingUnbinds() {
        pendingUnbinds.toList().forEach { (id, token) ->
            // 前一项回调可能重绑或强制销毁，跳过已失效的快照。
            if (pendingUnbinds[id] !== token) return@forEach
            pendingUnbinds.remove(id)
            notifyUnbind(id)
        }
    }

    private fun onDispose() {
        if (disposed) return
        disposed = true
        callInstanceDispose(value)
        value = null
        bindingSources.clear()
        directBindingSources.clear()
        pendingUnbinds.clear()
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
        val target = value ?: return
        val lifecycle = target as? InstanceLifeCycle ?: return
        try {
            lifecycle.onBind(arg, id)
        } catch (error: Throwable) {
            reportViewModelError(error, ErrorType.Lifecycle, "${target::class.qualifiedName} onBind error")
        }
    }

    private fun notifyUnbind(id: String) {
        val target = value ?: return
        val lifecycle = target as? InstanceLifeCycle ?: return
        try {
            lifecycle.onUnbind(arg, id)
        } catch (error: Throwable) {
            reportViewModelError(error, ErrorType.Lifecycle, "${target::class.qualifiedName} onUnbind error")
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
