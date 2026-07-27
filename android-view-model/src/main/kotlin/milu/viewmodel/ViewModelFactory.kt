package milu.viewmodel

import androidx.annotation.MainThread
import kotlin.reflect.KClass

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
