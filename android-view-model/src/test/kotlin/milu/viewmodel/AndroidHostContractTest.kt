package milu.viewmodel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.LocalView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidHostContractTest {
    @Before fun setUp() = ViewModel.reset()
    @After fun tearDown() = ViewModel.reset()

    @Test fun fragmentScopesFollowTheirActivityFragmentAndViewLifetimes() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
        val activity = controller.get()
        val container = FrameLayout(activity).apply { id = 12345 }
        activity.setContentView(container)
        val fragment = TestHostFragment()
        activity.supportFragmentManager.beginTransaction().add(container.id, fragment).commitNow()
        try {
            val activityBinding = activity.viewModelBinding
            val fragmentBinding = fragment.viewModelBinding
            val viewBinding = fragment.viewLifecycleViewModelBinding
            val spec = viewModelSpec { ViewModel() }
            val activityModel by activityBinding.readViewModel(spec)
            val fragmentModel by fragmentBinding.readViewModel(spec)
            val viewModel by viewBinding.readViewModel(spec)
            assertSame(activityBinding, fragment.activityViewModelBinding)
            assertSame(fragmentBinding, fragment.viewModelBinding)
            assertSame(viewBinding, fragment.viewLifecycleViewModelBinding)
            assertNotSame(activityModel, fragmentModel)
            assertNotSame(fragmentModel, viewModel)
            val oldView = fragment.requireView()
            activity.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
            assertTrue(viewBinding.isDisposed)
            assertNull(oldView.getTag(R.id.milu_view_model_binding))
            assertNull(oldView.getTag(R.id.milu_view_model_binding_lifecycle_owner))
            assertFalse(fragmentBinding.isDisposed)
            activity.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
            val replacement = fragment.viewLifecycleViewModelBinding
            assertNotSame(viewBinding, replacement)
            assertFalse(replacement.isDisposed)
            activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
            assertTrue(fragmentBinding.isDisposed)
            assertTrue(replacement.isDisposed)
            assertFalse(activityBinding.isDisposed)
        } finally {
            controller.pause().stop().destroy()
        }
        assertTrue(activity.viewModelStore.keys().isEmpty())
    }

    @Test fun fragmentViewBindingSurvivesWindowDetachWhileViewLifecycleIsAlive() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
        val activity = controller.get()
        val container = FrameLayout(activity).apply { id = 12346 }
        activity.setContentView(container)
        val fragment = TestHostFragment()
        activity.supportFragmentManager.beginTransaction().add(container.id, fragment).commitNow()
        try {
            val fragmentView = fragment.requireView()
            val viewBinding = fragment.viewLifecycleViewModelBinding
            val spec = viewModelSpec { ViewModel() }
            val model by viewBinding.readViewModel(spec)
            val before = model

            // Offscreen pager pages and cached list items leave the window without
            // destroying the Fragment view.
            container.removeView(fragmentView)
            assertFalse(viewBinding.isDisposed)
            assertSame(viewBinding, fragmentView.getTag(R.id.milu_view_model_binding))
            container.addView(fragmentView)
            assertSame(viewBinding, fragment.viewLifecycleViewModelBinding)
            assertSame(before, model)

            activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
            assertTrue(viewBinding.isDisposed)
            assertTrue(before.isDisposed)
            assertNull(fragmentView.getTag(R.id.milu_view_model_binding))
            assertNull(fragmentView.getTag(R.id.milu_view_model_binding_lifecycle_owner))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun viewTreeLookupRequiresOwnerAndUsesItsRetainedBinding() {
        val root = FrameLayout(RuntimeEnvironment.getApplication())
        val child = View(root.context)
        root.addView(child)
        assertThrows(IllegalStateException::class.java) { child.viewTreeViewModelBinding }
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        try {
            root.setViewTreeViewModelStoreOwner(owner)
            assertSame(owner.viewModelBinding, child.viewTreeViewModelBinding)
        } finally {
            owner.viewModelStore.clear()
        }
    }

    @Test fun retainedHolderIgnoresDestroyedOwnersAndCleansObserversWhenCleared() {
        val store = ViewModelStore()
        val owner = object : LifecycleOwner, ViewModelStoreOwner {
            override val viewModelStore = store
            override val lifecycle = LifecycleRegistry(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        val binding = owner.viewModelBinding
        assertSame(binding, owner.viewModelBinding)
        store.clear()
        assertTrue(binding.isDisposed)
        assertEquals(0, owner.lifecycle.observerCount)
        val holder = ViewModelBindingHolder()
        holder.binding.dispose()
        holder.attach(owner)
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        holder.attach(owner)
        holder.onDestroy(owner)
        val destroyedBinding = owner.viewModelBinding
        assertFalse(destroyedBinding.isPaused)
        store.clear()
    }

    @Test fun backgroundThreadCallsAreRejectedBeforeCreatingOwnership() {
        val failure = AtomicReference<Throwable>()
        val worker = Thread {
            try { ViewModelBinding() } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        worker.join()
        assertTrue(failure.get() is IllegalStateException)
        assertTrue(failure.get().message!!.contains("main thread"))
        assertEquals(0, InstanceManager.debugStoreCount)
    }

    @Test fun screenScopeWithoutStoreOwnerFallsBackToCompositionLifetime() {
        val recomposer = Recomposer(Dispatchers.Unconfined)
        val composition = Composition(EmptyApplier, recomposer)
        val view = View(RuntimeEnvironment.getApplication())
        var binding: ViewModelBinding? = null
        try {
            composition.setContent {
                CompositionLocalProvider(LocalView provides view) {
                    binding = rememberScreenViewModelBinding()
                }
            }
            assertNotNull(binding)
            assertFalse(binding!!.isDisposed)
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
        assertTrue(binding!!.isDisposed)
    }

    @Test fun lifecycleSourceUnsubscribesOnDestroy() {
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        val provider = LifecyclePauseProvider(owner)
        assertFalse(provider.isPaused)
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        assertEquals(0, owner.lifecycle.observerCount)
        assertThrows(ViewModelError::class.java) { provider.addOnPauseChanged {} }
        provider.dispose()
    }
}

class TestHostFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext())
}
