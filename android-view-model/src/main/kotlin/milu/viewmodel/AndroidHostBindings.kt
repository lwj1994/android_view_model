package milu.viewmodel

import android.view.View
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import java.util.Collections
import java.util.WeakHashMap
import androidx.lifecycle.ViewModel as AndroidXViewModel

internal class ViewModelBindingHolder : AndroidXViewModel() {
    val binding = ViewModelBinding()
    private val attachedOwners = Collections.newSetFromMap(WeakHashMap<LifecycleOwner, Boolean>())

    fun attach(owner: LifecycleOwner) {
        assertMainThread()
        if (!attachedOwners.add(owner)) return
        binding.addPauseProvider(LifecyclePauseProvider(owner))
    }

    override fun onCleared() {
        binding.dispose()
    }
}

/**
 * Binding retained by any AndroidX ViewModelStoreOwner.
 */
@get:MainThread
public val ViewModelStoreOwner.viewModelBinding: ViewModelBinding
    get() {
        assertMainThread()
        val holder = ViewModelProvider(this)[ViewModelBindingHolder::class.java]
        (this as? LifecycleOwner)?.let(holder::attach)
        return holder.binding
    }

/**
 * Activity-level binding, cleared with the Activity ViewModelStore.
 */
@get:MainThread
public val ComponentActivity.viewModelBinding: ViewModelBinding
    get() {
        assertMainThread()
        return (this as ViewModelStoreOwner).viewModelBinding
    }

/**
 * Fragment-level binding, cleared with the Fragment ViewModelStore.
 */
@get:MainThread
public val Fragment.viewModelBinding: ViewModelBinding
    get() {
        assertMainThread()
        return (this as ViewModelStoreOwner).viewModelBinding
    }

/**
 * Activity-shared binding for Fragment-to-Fragment communication.
 */
@get:MainThread
public val Fragment.activityViewModelBinding: ViewModelBinding
    get() {
        assertMainThread()
        return requireActivity().viewModelBinding
    }

/**
 * Fragment view-lifecycle binding, disposed when the Fragment view is destroyed.
 */
@get:MainThread
public val Fragment.viewLifecycleViewModelBinding: ViewModelBinding
    get() {
        assertMainThread()
        val owner = viewLifecycleOwner
        val view = requireView()
        val binding = view.viewModelBinding
        if (view.getTag(R.id.milu_view_model_binding_lifecycle_owner) !== owner) {
            binding.addPauseProvider(LifecyclePauseProvider(owner))
            owner.lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) {
                        binding.dispose()
                        view.setTag(R.id.milu_view_model_binding, null)
                        view.setTag(R.id.milu_view_model_binding_lifecycle_owner, null)
                    }
                },
            )
            view.setTag(R.id.milu_view_model_binding_lifecycle_owner, owner)
        }
        return binding
    }

/**
 * Binding owned by this View. It is disposed when the View detaches from the window.
 */
@get:MainThread
public val View.viewModelBinding: ViewModelBinding
    get() {
        assertMainThread()
        (getTag(R.id.milu_view_model_binding) as? ViewModelBinding)
            ?.takeUnless { it.isDisposed }
            ?.let { return it }

        val binding = ViewModelBinding()
        setTag(R.id.milu_view_model_binding, binding)
        addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit

                override fun onViewDetachedFromWindow(v: View) {
                    binding.dispose()
                    v.setTag(R.id.milu_view_model_binding, null)
                    v.setTag(R.id.milu_view_model_binding_lifecycle_owner, null)
                    v.removeOnAttachStateChangeListener(this)
                }
            },
        )
        return binding
    }

/**
 * Finds a ViewModelStoreOwner from the View tree and reuses its retained binding.
 */
@get:MainThread
public val View.viewTreeViewModelBinding: ViewModelBinding
    get() {
        assertMainThread()
        val owner = findViewTreeViewModelStoreOwner()
            ?: throw IllegalStateException("No ViewModelStoreOwner found from this View tree.")
        return owner.viewModelBinding
    }

/**
 * Binding scope that can be composed into any plain class.
 */
@MainThread
public class ViewModelBindingScope(
    onUpdate: (() -> Unit)? = null,
) : AutoCloseable {
    public val viewModelBinding: ViewModelBinding = if (onUpdate == null) {
        ViewModelBinding()
    } else {
        ViewModelBinding(onUpdate)
    }

    init {
        assertMainThread()
    }

    public fun dispose() {
        assertMainThread()
        viewModelBinding.dispose()
    }

    override fun close() {
        assertMainThread()
        dispose()
    }
}
