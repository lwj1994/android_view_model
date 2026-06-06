package milu.viewmodel

/**
 * Expected failures reported by the ViewModel system.
 */
public class ViewModelError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Error category passed to the global error sink.
 */
public enum class ErrorType {
    Listener,
    Lifecycle,
    Dispose,
    PauseResume,
}
