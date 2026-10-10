package milu.viewmodel

import androidx.annotation.MainThread
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * ViewModel factory declaration.
 */
@MainThread
public interface ViewModelFactory<VM : ViewModel> {
    public val modelClass: KClass<VM>

    public fun build(): VM

    public fun key(): Any? = null

    public fun tag(): Any? = null

    /** Returning true requires [key] to return a non-null explicit key. */
    public fun aliveForever(): Boolean = false
}

/**
 * A factory that preserves its declared Kotlin type, including generic arguments.
 * Use `typeOf<VM>()` for [modelType]. A null type is only supported for legacy KClass declarations
 * of non-generic top-level or static nested ViewModel classes. Keeping this capability separate
 * preserves existing factory implementations.
 */
@MainThread
public interface TypedViewModelFactory<VM : ViewModel> : ViewModelFactory<VM> {
    public val modelType: KType?
}
