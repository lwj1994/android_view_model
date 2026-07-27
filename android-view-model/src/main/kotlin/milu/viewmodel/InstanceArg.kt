package milu.viewmodel

/**
 * Metadata used to create and look up managed instances.
 *
 * - [key]: Cache key. Same type plus same key resolves to the same instance.
 *   Retained instances require a non-null explicit key.
 * - [tag]: Logical group label for batch lookups.
 * - [bindingId]: The [ViewModelBinding] that owns the current reference.
 * - [aliveForever]: Keep the instance alive after the reference count reaches zero.
 *   Requires [key] to be non-null.
 */
public data class InstanceArg(
    val key: Any? = null,
    val tag: Any? = null,
    val bindingId: String? = null,
    val aliveForever: Boolean = false,
)
