package milu.viewmodel

import android.view.View
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import java.util.WeakHashMap
import androidx.lifecycle.ViewModel as AndroidXViewModel

internal class ViewModelBindingHolder : AndroidXViewModel(), DefaultLifecycleObserver {
    val binding = ViewModelBinding()
    private val pauseProviders = WeakHashMap<LifecycleOwner, LifecyclePauseProvider>()

    fun attach(owner: LifecycleOwner) {
        assertMainThread()
        if (binding.isDisposed || owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
        if (pauseProviders.containsKey(owner)) return
        val provider = LifecyclePauseProvider(owner)
        pauseProviders[owner] = provider
        binding.addPauseProvider(provider, ownsProvider = true)
        owner.lifecycle.addObserver(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        assertMainThread()
        owner.lifecycle.removeObserver(this)
        // The binding can outlive an Activity across configuration changes. Disposing a
        // provider alone leaves its last (paused) state in the controller; remove it too.
        pauseProviders.remove(owner)?.let(binding::removePauseProvider)
    }

    override fun onCleared() {
        pauseProviders.keys.toList().forEach { it.lifecycle.removeObserver(this) }
        pauseProviders.clear()
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
 *
 * The binding follows `viewLifecycleOwner`, not window attachment. A Fragment view
 * can leave the window while its view lifecycle stays alive (for example an offscreen
 * ViewPager2 page cached by RecyclerView); the binding and its unkeyed ViewModels
 * survive that and are released on `ON_DESTROY` of the view lifecycle.
 */
@get:MainThread
public val Fragment.viewLifecycleViewModelBinding: ViewModelBinding
    get() {
        assertMainThread()
        val owner = viewLifecycleOwner
        val view = requireView()
        (view.getTag(R.id.milu_view_model_binding) as? ViewModelBinding)
            ?.takeUnless { it.isDisposed }
            ?.takeIf { view.getTag(R.id.milu_view_model_binding_lifecycle_owner) === owner }
            ?.let { return it }

        val binding = ViewModelBinding()
        binding.addPauseProvider(LifecyclePauseProvider(owner), ownsProvider = true)
        view.setTag(R.id.milu_view_model_binding, binding)
        view.setTag(R.id.milu_view_model_binding_lifecycle_owner, owner)
        owner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    owner.lifecycle.removeObserver(this)
                    binding.dispose()
                    if (view.getTag(R.id.milu_view_model_binding) === binding) {
                        view.setTag(R.id.milu_view_model_binding, null)
                        view.setTag(R.id.milu_view_model_binding_lifecycle_owner, null)
                    }
                }
            },
        )
        return binding
    }

/**
 * Finds a ViewModelStoreOwner from the View tree and reuses its retained binding.
 *
 * A plain View has no lifecycle of its own, so there is no View-scoped binding
 * property. A custom View either shares its owner's binding through this property,
 * or owns a [ViewModelBindingScope] explicitly: create it in `onAttachedToWindow`
 * and close it in `onDetachedFromWindow`.
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
