package melu.viewmodel

import androidx.annotation.MainThread

/**
 * Global observer for ViewModel lifecycle events.
 */
@MainThread
public interface ViewModelLifecycle {
    public fun onCreate(viewModel: ViewModel, arg: InstanceArg) {}

    public fun onBind(
        viewModel: ViewModel,
        arg: InstanceArg,
        bindingId: String,
    ) {
    }

    public fun onUnbind(
        viewModel: ViewModel,
        arg: InstanceArg,
        bindingId: String,
    ) {
    }

    public fun onDispose(viewModel: ViewModel, arg: InstanceArg) {}
}

/**
 * Lifecycle contract for instances managed by the registry.
 */
@MainThread
public interface InstanceLifeCycle {
    public fun onCreate(arg: InstanceArg)

    public fun onBind(
        arg: InstanceArg,
        bindingId: String,
    )

    public fun onUnbind(
        arg: InstanceArg,
        bindingId: String,
    )

    public fun onDispose(arg: InstanceArg)
}
