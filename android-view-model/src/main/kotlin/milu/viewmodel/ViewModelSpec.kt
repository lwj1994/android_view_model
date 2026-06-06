package milu.viewmodel

import androidx.annotation.MainThread
import kotlin.reflect.KClass

/**
 * Zero-argument ViewModel registration.
 */
@MainThread
public class ViewModelSpec<VM : ViewModel>(
    override val modelClass: KClass<VM>,
    private val key: Any? = null,
    private val tag: Any? = null,
    private val aliveForever: Boolean = false,
    private val builder: () -> VM,
) : ViewModelFactory<VM> {
    private var proxy: ViewModelSpec<VM>? = null

    public fun setProxy(spec: ViewModelSpec<VM>) {
        assertMainThread()
        proxy = spec
    }

    public fun clearProxy() {
        assertMainThread()
        proxy = null
    }

    override fun build(): VM {
        assertMainThread()
        return (proxy ?: this).builder()
    }

    override fun key(): Any? {
        assertMainThread()
        return (proxy ?: this).key
    }

    override fun tag(): Any? {
        assertMainThread()
        return (proxy ?: this).tag
    }

    override fun aliveForever(): Boolean {
        assertMainThread()
        return (proxy ?: this).aliveForever
    }
}

public inline fun <reified VM : ViewModel> viewModelSpec(
    key: Any? = null,
    tag: Any? = null,
    aliveForever: Boolean = false,
    noinline builder: () -> VM,
): ViewModelSpec<VM> = ViewModelSpec(
    modelClass = VM::class,
    key = key,
    tag = tag,
    aliveForever = aliveForever,
    builder = builder,
)

@MainThread
public class ViewModelSpecWithArg<VM : ViewModel, A>(
    private val modelClass: KClass<VM>,
    private val builder: (A) -> VM,
    private val key: ((A) -> Any?)? = null,
    private val tag: ((A) -> Any?)? = null,
    private val aliveForever: ((A) -> Boolean)? = null,
) {
    private var proxy: ViewModelSpecWithArg<VM, A>? = null

    public fun setProxy(spec: ViewModelSpecWithArg<VM, A>) {
        assertMainThread()
        proxy = spec
    }

    public fun clearProxy() {
        assertMainThread()
        proxy = null
    }

    public operator fun invoke(arg: A): ViewModelSpec<VM> {
        assertMainThread()
        val active = proxy ?: this
        return ViewModelSpec(
            modelClass = active.modelClass,
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
    modelClass = VM::class,
    builder = builder,
    key = key,
    tag = tag,
    aliveForever = aliveForever,
)

@MainThread
public class ViewModelSpecWithArg2<VM : ViewModel, A, B>(
    private val modelClass: KClass<VM>,
    private val builder: (A, B) -> VM,
    private val key: ((A, B) -> Any?)? = null,
    private val tag: ((A, B) -> Any?)? = null,
    private val aliveForever: ((A, B) -> Boolean)? = null,
) {
    private var proxy: ViewModelSpecWithArg2<VM, A, B>? = null

    public fun setProxy(spec: ViewModelSpecWithArg2<VM, A, B>) {
        assertMainThread()
        proxy = spec
    }

    public fun clearProxy() {
        assertMainThread()
        proxy = null
    }

    public operator fun invoke(
        arg1: A,
        arg2: B,
    ): ViewModelSpec<VM> {
        assertMainThread()
        val active = proxy ?: this
        return ViewModelSpec(
            modelClass = active.modelClass,
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
    modelClass = VM::class,
    builder = builder,
    key = key,
    tag = tag,
    aliveForever = aliveForever,
)

@MainThread
public class ViewModelSpecWithArg3<VM : ViewModel, A, B, C>(
    private val modelClass: KClass<VM>,
    private val builder: (A, B, C) -> VM,
    private val key: ((A, B, C) -> Any?)? = null,
    private val tag: ((A, B, C) -> Any?)? = null,
    private val aliveForever: ((A, B, C) -> Boolean)? = null,
) {
    private var proxy: ViewModelSpecWithArg3<VM, A, B, C>? = null

    public fun setProxy(spec: ViewModelSpecWithArg3<VM, A, B, C>) {
        assertMainThread()
        proxy = spec
    }

    public fun clearProxy() {
        assertMainThread()
        proxy = null
    }

    public operator fun invoke(
        arg1: A,
        arg2: B,
        arg3: C,
    ): ViewModelSpec<VM> {
        assertMainThread()
        val active = proxy ?: this
        return ViewModelSpec(
            modelClass = active.modelClass,
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
    modelClass = VM::class,
    builder = builder,
    key = key,
    tag = tag,
    aliveForever = aliveForever,
)

@MainThread
public class ViewModelSpecWithArg4<VM : ViewModel, A, B, C, D>(
    private val modelClass: KClass<VM>,
    private val builder: (A, B, C, D) -> VM,
    private val key: ((A, B, C, D) -> Any?)? = null,
    private val tag: ((A, B, C, D) -> Any?)? = null,
    private val aliveForever: ((A, B, C, D) -> Boolean)? = null,
) {
    private var proxy: ViewModelSpecWithArg4<VM, A, B, C, D>? = null

    public fun setProxy(spec: ViewModelSpecWithArg4<VM, A, B, C, D>) {
        assertMainThread()
        proxy = spec
    }

    public fun clearProxy() {
        assertMainThread()
        proxy = null
    }

    public operator fun invoke(
        arg1: A,
        arg2: B,
        arg3: C,
        arg4: D,
    ): ViewModelSpec<VM> {
        assertMainThread()
        val active = proxy ?: this
        return ViewModelSpec(
            modelClass = active.modelClass,
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
    modelClass = VM::class,
    builder = builder,
    key = key,
    tag = tag,
    aliveForever = aliveForever,
)
