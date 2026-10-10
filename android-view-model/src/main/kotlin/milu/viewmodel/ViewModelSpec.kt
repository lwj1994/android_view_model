package milu.viewmodel

import androidx.annotation.MainThread
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Zero-argument ViewModel registration. Keep specs stable (normally at module/file scope) and
 * pass them to [ViewModelBinding.watch] or [ViewModelBinding.read]; this is the primary
 * resolution path.
 *
 * Every retained spec must configure a non-null explicit key when
 * [aliveForever] is true.
 */
@MainThread
public class ViewModelSpec<VM : ViewModel> internal constructor(
    internal val instanceType: InstanceType<VM>,
    private val key: Any? = null,
    private val tag: Any? = null,
    private val aliveForever: Boolean = false,
    private val builder: () -> VM,
) : TypedViewModelFactory<VM> {
    /** Compatibility constructor for a non-generic top-level or static nested ViewModel class. */
    public constructor(
        modelClass: KClass<VM>,
        key: Any? = null,
        tag: Any? = null,
        aliveForever: Boolean = false,
        builder: () -> VM,
    ) : this(InstanceType(modelClass), key, tag, aliveForever, builder)

    /** Declares a ViewModel type with all generic arguments, normally captured by [viewModelSpec]. */
    public constructor(
        modelType: KType,
        key: Any? = null,
        tag: Any? = null,
        aliveForever: Boolean = false,
        builder: () -> VM,
    ) : this(instanceTypeOf(modelType), key, tag, aliveForever, builder)

    override val modelClass: KClass<VM>
        get() = instanceType.modelClass

    override val modelType: KType?
        get() = instanceType.kotlinType

    private val proxyState = ViewModelSpecProxyState<ViewModelSpec<VM>>()

    public fun setProxy(spec: ViewModelSpec<VM>) {
        assertMainThread()
        proxyState.setProxy(spec)
    }

    public fun clearProxy() {
        assertMainThread()
        proxyState.clearProxy()
    }

    /** Installs an idempotently restorable scoped override. */
    public fun overrideWith(spec: ViewModelSpec<VM>): () -> Unit {
        assertMainThread()
        return proxyState.overrideWith(spec)
    }

    /** Runs [block] with a coroutine-local override and always restores it. */
    public suspend fun <Result> runWithOverride(
        spec: ViewModelSpec<VM>,
        block: suspend () -> Result,
    ): Result = proxyState.runWithOverride(spec, block)

    private val activeProxy: ViewModelSpec<VM>?
        get() = proxyState.activeProxy

    override fun build(): VM {
        assertMainThread()
        val active = activeProxy
        return if (active == null) builder() else active.build()
    }

    override fun key(): Any? {
        assertMainThread()
        val active = activeProxy
        return if (active == null) key else active.key()
    }

    override fun tag(): Any? {
        assertMainThread()
        val active = activeProxy
        return if (active == null) tag else active.tag()
    }

    override fun aliveForever(): Boolean {
        assertMainThread()
        val active = activeProxy
        return if (active == null) aliveForever else active.aliveForever()
    }
}

public inline fun <reified VM : ViewModel> viewModelSpec(
    key: Any? = null,
    tag: Any? = null,
    aliveForever: Boolean = false,
    noinline builder: () -> VM,
): ViewModelSpec<VM> = ViewModelSpec(
    modelType = typeOf<VM>(),
    key = key,
    tag = tag,
    aliveForever = aliveForever,
    builder = builder,
)

@MainThread
public class ViewModelSpecWithArg<VM : ViewModel, A> internal constructor(
    private val instanceType: InstanceType<VM>,
    private val builder: (A) -> VM,
    private val key: ((A) -> Any?)? = null,
    private val tag: ((A) -> Any?)? = null,
    private val aliveForever: ((A) -> Boolean)? = null,
) {
    /** Compatibility constructor for a non-generic top-level or static nested ViewModel class. */
    public constructor(
        modelClass: KClass<VM>,
        builder: (A) -> VM,
        key: ((A) -> Any?)? = null,
        tag: ((A) -> Any?)? = null,
        aliveForever: ((A) -> Boolean)? = null,
    ) : this(InstanceType(modelClass), builder, key, tag, aliveForever)

    /** Preserves the declared ViewModel's generic arguments across argument resolution. */
    public constructor(
        modelType: KType,
        builder: (A) -> VM,
        key: ((A) -> Any?)? = null,
        tag: ((A) -> Any?)? = null,
        aliveForever: ((A) -> Boolean)? = null,
    ) : this(instanceTypeOf(modelType), builder, key, tag, aliveForever)

    private val proxyState = ViewModelSpecProxyState<ViewModelSpecWithArg<VM, A>>()

    public fun setProxy(spec: ViewModelSpecWithArg<VM, A>) {
        assertMainThread()
        proxyState.setProxy(spec)
    }

    public fun clearProxy() {
        assertMainThread()
        proxyState.clearProxy()
    }

    public fun overrideWith(spec: ViewModelSpecWithArg<VM, A>): () -> Unit {
        assertMainThread()
        return proxyState.overrideWith(spec)
    }

    public suspend fun <Result> runWithOverride(
        spec: ViewModelSpecWithArg<VM, A>,
        block: suspend () -> Result,
    ): Result = proxyState.runWithOverride(spec, block)

    public operator fun invoke(arg: A): ViewModelSpec<VM> {
        assertMainThread()
        val active = proxyState.activeProxy ?: this
        return ViewModelSpec(
            instanceType = active.instanceType,
            key = active.key?.invoke(arg),
            tag = active.tag?.invoke(arg),
            aliveForever = active.aliveForever?.invoke(arg) ?: false,
            builder = { active.builder(arg) },
        )
    }
}

public inline fun <reified VM : ViewModel, A> viewModelSpecWithArg(
    noinline builder: (A) -> VM,
    noinline key: ((A) -> Any?)? = null,
    noinline tag: ((A) -> Any?)? = null,
    noinline aliveForever: ((A) -> Boolean)? = null,
): ViewModelSpecWithArg<VM, A> = ViewModelSpecWithArg(
    modelType = typeOf<VM>(),
    builder = builder,
    key = key,
    tag = tag,
    aliveForever = aliveForever,
)

@MainThread
public class ViewModelSpecWithArg2<VM : ViewModel, A, B> internal constructor(
    private val instanceType: InstanceType<VM>,
    private val builder: (A, B) -> VM,
    private val key: ((A, B) -> Any?)? = null,
    private val tag: ((A, B) -> Any?)? = null,
    private val aliveForever: ((A, B) -> Boolean)? = null,
) {
    /** Compatibility constructor for a non-generic top-level or static nested ViewModel class. */
    public constructor(
        modelClass: KClass<VM>,
        builder: (A, B) -> VM,
        key: ((A, B) -> Any?)? = null,
        tag: ((A, B) -> Any?)? = null,
        aliveForever: ((A, B) -> Boolean)? = null,
    ) : this(InstanceType(modelClass), builder, key, tag, aliveForever)

    /** Preserves the declared ViewModel's generic arguments across argument resolution. */
    public constructor(
        modelType: KType,
        builder: (A, B) -> VM,
        key: ((A, B) -> Any?)? = null,
        tag: ((A, B) -> Any?)? = null,
        aliveForever: ((A, B) -> Boolean)? = null,
    ) : this(instanceTypeOf(modelType), builder, key, tag, aliveForever)

    private val proxyState = ViewModelSpecProxyState<ViewModelSpecWithArg2<VM, A, B>>()

    public fun setProxy(spec: ViewModelSpecWithArg2<VM, A, B>) {
        assertMainThread()
        proxyState.setProxy(spec)
    }

    public fun clearProxy() {
        assertMainThread()
        proxyState.clearProxy()
    }

    public fun overrideWith(spec: ViewModelSpecWithArg2<VM, A, B>): () -> Unit {
        assertMainThread()
        return proxyState.overrideWith(spec)
    }

    public suspend fun <Result> runWithOverride(
        spec: ViewModelSpecWithArg2<VM, A, B>,
        block: suspend () -> Result,
    ): Result = proxyState.runWithOverride(spec, block)

    public operator fun invoke(
        arg1: A,
        arg2: B,
    ): ViewModelSpec<VM> {
        assertMainThread()
        val active = proxyState.activeProxy ?: this
        return ViewModelSpec(
            instanceType = active.instanceType,
            key = active.key?.invoke(arg1, arg2),
            tag = active.tag?.invoke(arg1, arg2),
            aliveForever = active.aliveForever?.invoke(arg1, arg2) ?: false,
            builder = { active.builder(arg1, arg2) },
        )
    }
}

public inline fun <reified VM : ViewModel, A, B> viewModelSpecWithArg2(
    noinline builder: (A, B) -> VM,
    noinline key: ((A, B) -> Any?)? = null,
    noinline tag: ((A, B) -> Any?)? = null,
    noinline aliveForever: ((A, B) -> Boolean)? = null,
): ViewModelSpecWithArg2<VM, A, B> = ViewModelSpecWithArg2(
    modelType = typeOf<VM>(),
    builder = builder,
    key = key,
    tag = tag,
    aliveForever = aliveForever,
)

@MainThread
public class ViewModelSpecWithArg3<VM : ViewModel, A, B, C> internal constructor(
    private val instanceType: InstanceType<VM>,
    private val builder: (A, B, C) -> VM,
    private val key: ((A, B, C) -> Any?)? = null,
    private val tag: ((A, B, C) -> Any?)? = null,
    private val aliveForever: ((A, B, C) -> Boolean)? = null,
) {
    /** Compatibility constructor for a non-generic top-level or static nested ViewModel class. */
    public constructor(
        modelClass: KClass<VM>,
        builder: (A, B, C) -> VM,
        key: ((A, B, C) -> Any?)? = null,
        tag: ((A, B, C) -> Any?)? = null,
        aliveForever: ((A, B, C) -> Boolean)? = null,
    ) : this(InstanceType(modelClass), builder, key, tag, aliveForever)

    /** Preserves the declared ViewModel's generic arguments across argument resolution. */
    public constructor(
        modelType: KType,
        builder: (A, B, C) -> VM,
        key: ((A, B, C) -> Any?)? = null,
        tag: ((A, B, C) -> Any?)? = null,
        aliveForever: ((A, B, C) -> Boolean)? = null,
    ) : this(instanceTypeOf(modelType), builder, key, tag, aliveForever)

    private val proxyState = ViewModelSpecProxyState<ViewModelSpecWithArg3<VM, A, B, C>>()

    public fun setProxy(spec: ViewModelSpecWithArg3<VM, A, B, C>) {
        assertMainThread()
        proxyState.setProxy(spec)
    }

    public fun clearProxy() {
        assertMainThread()
        proxyState.clearProxy()
    }

    public fun overrideWith(spec: ViewModelSpecWithArg3<VM, A, B, C>): () -> Unit {
        assertMainThread()
        return proxyState.overrideWith(spec)
    }

    public suspend fun <Result> runWithOverride(
        spec: ViewModelSpecWithArg3<VM, A, B, C>,
        block: suspend () -> Result,
    ): Result = proxyState.runWithOverride(spec, block)

    public operator fun invoke(
        arg1: A,
        arg2: B,
        arg3: C,
    ): ViewModelSpec<VM> {
        assertMainThread()
        val active = proxyState.activeProxy ?: this
        return ViewModelSpec(
            instanceType = active.instanceType,
            key = active.key?.invoke(arg1, arg2, arg3),
            tag = active.tag?.invoke(arg1, arg2, arg3),
            aliveForever = active.aliveForever?.invoke(arg1, arg2, arg3) ?: false,
            builder = { active.builder(arg1, arg2, arg3) },
        )
    }
}

public inline fun <reified VM : ViewModel, A, B, C> viewModelSpecWithArg3(
    noinline builder: (A, B, C) -> VM,
    noinline key: ((A, B, C) -> Any?)? = null,
    noinline tag: ((A, B, C) -> Any?)? = null,
    noinline aliveForever: ((A, B, C) -> Boolean)? = null,
): ViewModelSpecWithArg3<VM, A, B, C> = ViewModelSpecWithArg3(
    modelType = typeOf<VM>(),
    builder = builder,
    key = key,
    tag = tag,
    aliveForever = aliveForever,
)

@MainThread
public class ViewModelSpecWithArg4<VM : ViewModel, A, B, C, D> internal constructor(
    private val instanceType: InstanceType<VM>,
    private val builder: (A, B, C, D) -> VM,
    private val key: ((A, B, C, D) -> Any?)? = null,
    private val tag: ((A, B, C, D) -> Any?)? = null,
    private val aliveForever: ((A, B, C, D) -> Boolean)? = null,
) {
    /** Compatibility constructor for a non-generic top-level or static nested ViewModel class. */
    public constructor(
        modelClass: KClass<VM>,
        builder: (A, B, C, D) -> VM,
        key: ((A, B, C, D) -> Any?)? = null,
        tag: ((A, B, C, D) -> Any?)? = null,
        aliveForever: ((A, B, C, D) -> Boolean)? = null,
    ) : this(InstanceType(modelClass), builder, key, tag, aliveForever)

    /** Preserves the declared ViewModel's generic arguments across argument resolution. */
    public constructor(
        modelType: KType,
        builder: (A, B, C, D) -> VM,
        key: ((A, B, C, D) -> Any?)? = null,
        tag: ((A, B, C, D) -> Any?)? = null,
        aliveForever: ((A, B, C, D) -> Boolean)? = null,
    ) : this(instanceTypeOf(modelType), builder, key, tag, aliveForever)

    private val proxyState = ViewModelSpecProxyState<ViewModelSpecWithArg4<VM, A, B, C, D>>()

    public fun setProxy(spec: ViewModelSpecWithArg4<VM, A, B, C, D>) {
        assertMainThread()
        proxyState.setProxy(spec)
    }

    public fun clearProxy() {
        assertMainThread()
        proxyState.clearProxy()
    }

    public fun overrideWith(spec: ViewModelSpecWithArg4<VM, A, B, C, D>): () -> Unit {
        assertMainThread()
        return proxyState.overrideWith(spec)
    }

    public suspend fun <Result> runWithOverride(
        spec: ViewModelSpecWithArg4<VM, A, B, C, D>,
        block: suspend () -> Result,
    ): Result = proxyState.runWithOverride(spec, block)

    public operator fun invoke(
        arg1: A,
        arg2: B,
        arg3: C,
        arg4: D,
    ): ViewModelSpec<VM> {
        assertMainThread()
        val active = proxyState.activeProxy ?: this
        return ViewModelSpec(
            instanceType = active.instanceType,
            key = active.key?.invoke(arg1, arg2, arg3, arg4),
            tag = active.tag?.invoke(arg1, arg2, arg3, arg4),
            aliveForever = active.aliveForever?.invoke(arg1, arg2, arg3, arg4) ?: false,
            builder = { active.builder(arg1, arg2, arg3, arg4) },
        )
    }
}

public inline fun <reified VM : ViewModel, A, B, C, D> viewModelSpecWithArg4(
    noinline builder: (A, B, C, D) -> VM,
    noinline key: ((A, B, C, D) -> Any?)? = null,
    noinline tag: ((A, B, C, D) -> Any?)? = null,
    noinline aliveForever: ((A, B, C, D) -> Boolean)? = null,
): ViewModelSpecWithArg4<VM, A, B, C, D> = ViewModelSpecWithArg4(
    modelType = typeOf<VM>(),
    builder = builder,
    key = key,
    tag = tag,
    aliveForever = aliveForever,
)
