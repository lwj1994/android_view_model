package milu.viewmodel

import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * Business ViewModel base class.
 *
 * This class intentionally does not extend AndroidX ViewModel. Business instances are managed by
 * this library's key/tag registry and reference counting. AndroidX ViewModel is used only at the
 * host layer to retain [ViewModelBinding] for Activity, Fragment, and Compose owners.
 */
@MainThread
public open class ViewModel(
    coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Main.immediate,
) : InstanceLifeCycle {
    public companion object {
        private val initialized = AtomicBoolean(false)
        private var isResetting = false

        public val config: ViewModelConfig
            get() = ViewModelGlobalConfig.current

        public fun initialize(
            config: ViewModelConfig = ViewModelConfig(),
            lifecycles: List<ViewModelLifecycle> = emptyList(),
        ) {
            assertMainThread()
            if (!initialized.compareAndSet(false, true)) return
            ViewModelGlobalConfig.set(config)
            ViewModelLifecycleRegistry.lifecycles.addAll(lifecycles)
        }

        public fun addLifecycle(lifecycle: ViewModelLifecycle): () -> Unit {
            assertMainThread()
            ViewModelLifecycleRegistry.lifecycles += lifecycle
            return { removeLifecycle(lifecycle) }
        }

        public fun removeLifecycle(lifecycle: ViewModelLifecycle) {
            assertMainThread()
            ViewModelLifecycleRegistry.lifecycles -= lifecycle
        }

        /**
         * Advanced lookup-only API for an instance already created by another owner.
         * Prefer [ViewModelBinding.read] with a stable spec for normal dependency resolution.
         */
        public inline fun <reified VM : ViewModel> readCached(
            key: Any? = null,
            tag: Any? = null,
        ): VM = readCached(VM::class, key, tag)

        /**
         * Advanced lookup-only API that returns `null` on a miss.
         * Prefer spec-based resolution in normal application code.
         */
        public inline fun <reified VM : ViewModel> maybeReadCached(
            key: Any? = null,
            tag: Any? = null,
        ): VM? = try {
            readCached<VM>(key = key, tag = tag)
        } catch (_: ViewModelError) {
            null
        }

        @PublishedApi
        internal fun <VM : ViewModel> readCached(
            modelClass: KClass<VM>,
            key: Any?,
            tag: Any?,
        ): VM {
            assertMainThread()
            return InstanceManager.get(
                modelClass,
                InstanceFactory(arg = InstanceArg(key = key, tag = tag)),
            )
        }

        /**
         * Completely resets the process-wide ViewModel runtime.
         *
         * Cached instances, including `aliveForever` instances, are disposed
         * before configuration and lifecycle observers are cleared so cleanup
         * errors still use the active error pipeline.
         */
        public fun reset() {
            assertMainThread()
            // Cover the entire reset. A nested reset from one generation's
            // teardown must not clear the error/lifecycle pipeline while the
            // outer reset is still disposing its remaining peers.
            if (isResetting) return
            isResetting = true
            try {
                InstanceManager.debugReset()
                initialized.set(false)
                ViewModelGlobalConfig.reset()
                ViewModelLifecycleRegistry.lifecycles.clear()
            } finally {
                isResetting = false
            }
        }

        /** Compatibility alias for older tests and tooling. */
        @Deprecated("Use reset()", ReplaceWith("reset()"))
        public fun debugReset() {
            reset()
        }
    }

    init {
        assertMainThread()
    }

    public var instanceArg: InstanceArg = InstanceArg()
        private set

    public var isDisposed: Boolean = false
        private set

    public val tag: Any?
        get() = instanceArg.tag

    public val hasListeners: Boolean
        get() = listeners.isNotEmpty()

    public val viewModelScope: CoroutineScope = CoroutineScope(coroutineContext)

    public val refHandler: ViewModelBindingHandler = ViewModelBindingHandler()

    public open val viewModelBinding: ViewModelBinding
        get() {
            if (isDisposed) {
                throw ViewModelError(
                    "Cannot resolve dependencies from a disposed ${this::class.qualifiedName}.",
                )
            }
            return dependencyBinding ?: ViewModelDependencyBinding(
                parent = this,
                parentHandler = refHandler,
                onDependencyUpdate = ::handleDependencyUpdate,
            ).also { dependencyBinding = it }
        }

    private val listeners = linkedMapOf<String, () -> Unit>()
    private val autoDispose = AutoDisposeController()
    private var dependencyBinding: ViewModelDependencyBinding? = null

    internal val dependencyBindingIfCreated: ViewModelDependencyBinding?
        get() = dependencyBinding

    public fun listen(onChanged: () -> Unit): () -> Unit {
        assertMainThread()
        val id = UUID.randomUUID().toString()
        listeners[id] = onChanged
        return { listeners.remove(id) }
    }

    public open fun notifyListeners() {
        assertMainThread()
        if (isDisposed) {
            viewModelLog { "${this::class.qualifiedName}: notifyListeners after disposed" }
            return
        }
        runInViewModelUpdateTransaction {
            val snapshot = listeners.toList()
            snapshot.forEach { (id, listener) ->
                if (listeners[id] !== listener) return@forEach
                try {
                    listener()
                } catch (error: Throwable) {
                    reportViewModelError(error, ErrorType.Listener, "notifyListeners error")
                }
            }
        }
    }

    /** Called before a watched child notification is forwarded through this ViewModel. */
    protected open fun onDependencyNotify(viewModel: ViewModel) {}

    private fun handleDependencyUpdate(viewModel: ViewModel) {
        if (isDisposed) return
        onDependencyNotify(viewModel)
        notifyListeners()
    }

    public fun update(block: () -> Unit) {
        assertMainThread()
        block()
        notifyListeners()
    }

    public suspend fun updateAsync(block: suspend () -> Unit) {
        assertMainThread()
        block()
        withContext(Dispatchers.Main.immediate) {
            notifyListeners()
        }
    }

    public fun addDispose(block: () -> Unit) {
        assertMainThread()
        autoDispose.addDispose(block)
    }

    override fun onCreate(arg: InstanceArg) {
        assertMainThread()
        instanceArg = arg
        ViewModelLifecycleRegistry.lifecycles.forEach { lifecycle ->
            try {
                lifecycle.onCreate(this, arg)
            } catch (error: Throwable) {
                reportViewModelError(error, ErrorType.Lifecycle, "Lifecycle onCreate error")
            }
        }
    }

    override fun onBind(
        arg: InstanceArg,
        bindingId: String,
    ) {
        assertMainThread()
        ViewModelLifecycleRegistry.lifecycles.forEach { lifecycle ->
            try {
                lifecycle.onBind(this, arg, bindingId)
            } catch (error: Throwable) {
                reportViewModelError(error, ErrorType.Lifecycle, "Lifecycle onBind error")
            }
        }
    }

    override fun onUnbind(
        arg: InstanceArg,
        bindingId: String,
    ) {
        assertMainThread()
        ViewModelLifecycleRegistry.lifecycles.forEach { lifecycle ->
            try {
                lifecycle.onUnbind(this, arg, bindingId)
            } catch (error: Throwable) {
                reportViewModelError(error, ErrorType.Lifecycle, "Lifecycle onUnbind error")
            }
        }
    }

    override fun onDispose(arg: InstanceArg) {
        assertMainThread()
        if (isDisposed) return
        isDisposed = true
        autoDispose.dispose()
        dependencyBinding?.dispose()
        refHandler.dispose()
        viewModelScope.cancel()
        try {
            dispose()
        } catch (error: Throwable) {
            reportViewModelError(error, ErrorType.Dispose, "${this::class.qualifiedName} dispose error")
        }
        ViewModelLifecycleRegistry.lifecycles.forEach { lifecycle ->
            try {
                lifecycle.onDispose(this, arg)
            } catch (error: Throwable) {
                reportViewModelError(error, ErrorType.Dispose, "Lifecycle onDispose error")
            }
        }
        listeners.clear()
    }

    /**
     * Subclass teardown hook. Overriding methods do not need to call super.
     */
    protected open fun dispose() {}
}
