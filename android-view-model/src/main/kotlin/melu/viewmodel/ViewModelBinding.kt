package melu.viewmodel

import androidx.annotation.MainThread
import java.util.UUID

/**
 * ViewModel container that can be held by Activity, Fragment, View, Compose, or any plain class.
 */
@MainThread
public open class ViewModelBinding {
    public companion object {
        private val buildingStack = object : ThreadLocal<ArrayDeque<ViewModelBinding>>() {
            override fun initialValue(): ArrayDeque<ViewModelBinding> = ArrayDeque()
        }

        internal val currentBuilding: ViewModelBinding?
            get() = stack().lastOrNull()

        internal fun <R> withBuilding(
            binding: ViewModelBinding,
            block: () -> R,
        ): R {
            val stack = stack()
            stack.addLast(binding)
            return try {
                block()
            } finally {
                stack.removeLast()
            }
        }

        private fun stack(): ArrayDeque<ViewModelBinding> =
            buildingStack.get() ?: ArrayDeque<ViewModelBinding>().also(buildingStack::set)
    }

    public val id: String = "Binding#${UUID.randomUUID()}"

    public var isDisposed: Boolean = false
        private set

    public val isPaused: Boolean
        get() = pauseController.isPaused

    private val watchedViewModels = linkedSetOf<ViewModel>()
    private val disposes = mutableListOf<() -> Unit>()
    private val updateListeners = linkedMapOf<String, () -> Unit>()
    private var hasMissedUpdates = false

    private val instanceController = AutoDisposeInstanceController(
        binding = this,
        onRecreate = { onUpdate() },
    )

    public val pauseController: PauseAwareController = makePauseController()

    public constructor()

    public constructor(onUpdate: () -> Unit) {
        assertMainThread()
        addUpdateListener(onUpdate)
    }

    init {
        assertMainThread()
    }

    protected open fun makePauseController(): PauseAwareController = PauseAwareController(
        onPause = { onPause() },
        onResume = { onResume() },
    )

    public open fun onUpdate() {
        assertMainThread()
        updateListeners.values.toList().forEach { listener ->
            try {
                listener()
            } catch (error: Throwable) {
                reportViewModelError(error, ErrorType.Listener, "ViewModelBinding update listener error")
            }
        }
    }

    public open fun onPause() {
        assertMainThread()
    }

    public open fun onResume() {
        assertMainThread()
        if (!hasMissedUpdates) return
        hasMissedUpdates = false
        onUpdate()
        viewModelLog { "${this::class.qualifiedName} resumed with missed updates" }
    }

    public fun addUpdateListener(listener: () -> Unit): () -> Unit {
        assertMainThread()
        val id = UUID.randomUUID().toString()
        updateListeners[id] = listener
        return { updateListeners.remove(id) }
    }

    public fun <VM : ViewModel> watch(factory: ViewModelFactory<VM>): VM =
        getViewModel(factory = factory, listen = true)

    public fun <VM : ViewModel> read(factory: ViewModelFactory<VM>): VM =
        getViewModel(factory = factory, listen = false)

    public inline fun <reified VM : ViewModel> watchCached(
        key: Any? = null,
        tag: Any? = null,
    ): VM = requireExistingViewModel(
        modelClass = VM::class,
        arg = InstanceArg(key = key, tag = tag),
        listen = true,
    )

    public inline fun <reified VM : ViewModel> readCached(
        key: Any? = null,
        tag: Any? = null,
    ): VM = requireExistingViewModel(
        modelClass = VM::class,
        arg = InstanceArg(key = key, tag = tag),
        listen = false,
    )

    public inline fun <reified VM : ViewModel> maybeWatchCached(
        key: Any? = null,
        tag: Any? = null,
    ): VM? = runCatching { watchCached<VM>(key = key, tag = tag) }.getOrNull()

    public inline fun <reified VM : ViewModel> maybeReadCached(
        key: Any? = null,
        tag: Any? = null,
    ): VM? = runCatching { readCached<VM>(key = key, tag = tag) }.getOrNull()

    public inline fun <reified VM : ViewModel> watchCachesByTag(tag: Any): List<VM> {
        return watchCachesByTag(VM::class, tag)
    }

    @PublishedApi
    internal fun <VM : ViewModel> watchCachesByTag(
        modelClass: kotlin.reflect.KClass<VM>,
        tag: Any,
    ): List<VM> {
        assertMainThread()
        val vms = instanceController.getInstancesByTag(modelClass, tag, observeRecreate = true)
        vms.forEach(::addListener)
        return vms
    }

    public inline fun <reified VM : ViewModel> readCachesByTag(tag: Any): List<VM> {
        return readCachesByTag(VM::class, tag)
    }

    @PublishedApi
    internal fun <VM : ViewModel> readCachesByTag(
        modelClass: kotlin.reflect.KClass<VM>,
        tag: Any,
    ): List<VM> {
        assertMainThread()
        return instanceController.getInstancesByTag(modelClass, tag, observeRecreate = true)
    }

    public fun <VM : ViewModel> listen(
        factory: ViewModelFactory<VM>,
        onChanged: () -> Unit,
    ) {
        assertMainThread()
        val vm = read(factory)
        disposes += vm.listen(onChanged)
    }

    public fun <S, VM : StateViewModel<S>> listenState(
        factory: ViewModelFactory<VM>,
        onChanged: (S?, S) -> Unit,
    ) {
        assertMainThread()
        val vm = read(factory)
        disposes += vm.listenState(onChanged)
    }

    public fun <S, R, VM : StateViewModel<S>> listenStateSelect(
        factory: ViewModelFactory<VM>,
        selector: (S) -> R,
        onChanged: (R?, R) -> Unit,
    ) {
        assertMainThread()
        val vm = read(factory)
        disposes += vm.listenStateSelect(selector, onChanged)
    }

    public fun <VM : ViewModel> recycle(viewModel: VM) {
        assertMainThread()
        instanceController.recycle(viewModel)
        onUpdate()
    }

    public fun addPauseProvider(provider: ViewModelBindingPauseProvider) {
        assertMainThread()
        pauseController.addProvider(provider)
    }

    public fun removePauseProvider(provider: ViewModelBindingPauseProvider) {
        assertMainThread()
        pauseController.removeProvider(provider)
    }

    public open fun dispose() {
        assertMainThread()
        if (isDisposed) return
        isDisposed = true
        watchedViewModels.clear()
        disposes.toList().forEach { disposer ->
            try {
                disposer()
            } catch (error: Throwable) {
                reportViewModelError(error, ErrorType.Dispose, "listener disposer error")
            }
        }
        disposes.clear()
        updateListeners.clear()
        pauseController.dispose()
        instanceController.dispose()
    }

    internal fun registerListenerDisposer(disposer: () -> Unit) {
        assertMainThread()
        disposes += disposer
    }

    private fun <VM : ViewModel> getViewModel(
        factory: ViewModelFactory<VM>,
        listen: Boolean,
    ): VM {
        assertMainThread()
        check(!isDisposed) { "Cannot get ${factory.modelClass.qualifiedName}: binding is disposed." }
        val key = factory.key() ?: UUID.randomUUID().toString()
        val instanceFactory = InstanceFactory(
            builder = {
                withBuilding(this) {
                    val vm = factory.build()
                    vm.refHandler.addRef(this)
                    vm
                }
            },
            arg = InstanceArg(
                key = key,
                tag = factory.tag(),
                aliveForever = factory.aliveForever(),
            ),
        )
        val vm = instanceController.getInstance(factory.modelClass, instanceFactory)
        if (listen) addListener(vm)
        return vm
    }

    @PublishedApi
    internal fun <VM : ViewModel> requireExistingViewModel(
        modelClass: kotlin.reflect.KClass<VM>,
        arg: InstanceArg,
        listen: Boolean,
    ): VM {
        assertMainThread()
        check(!isDisposed) { "Cannot get ${modelClass.qualifiedName}: binding is disposed." }
        val vm = instanceController.getInstance(
            modelClass,
            InstanceFactory(arg = arg),
        )
        if (listen) addListener(vm)
        return vm
    }

    private fun addListener(vm: ViewModel) {
        assertMainThread()
        if (!watchedViewModels.add(vm)) return
        val disposer = vm.listen {
            if (isDisposed) return@listen
            if (pauseController.isPaused) {
                hasMissedUpdates = true
                viewModelLog { "${this::class.qualifiedName} paused, delay update" }
                return@listen
            }
            onUpdate()
        }
        disposes += disposer
    }
}
