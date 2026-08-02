package milu.viewmodel

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private class ViewModelSpecOverrideEntry(
    val spec: Any,
)

/** Coroutine-local override scope. Parent chaining preserves nested scopes. */
private class ViewModelSpecOverrideContext(
    val parent: ViewModelSpecOverrideContext?,
) {
    private val entries = IdentityHashMap<Any, MutableList<ViewModelSpecOverrideEntry>>()

    fun append(
        owner: Any,
        entry: ViewModelSpecOverrideEntry,
    ) {
        entries.getOrPut(owner) { mutableListOf() } += entry
    }

    fun remove(
        owner: Any,
        entry: ViewModelSpecOverrideEntry,
    ) {
        val current = entries[owner] ?: return
        current.removeAll { it === entry }
        if (current.isEmpty()) entries.remove(owner)
    }

    fun activeSpec(owner: Any): Any? = entries[owner]?.lastOrNull()?.spec
        ?: parent?.activeSpec(owner)
}

private object ViewModelSpecOverrideRuntime {
    val current: ThreadLocal<ViewModelSpecOverrideContext?> = ThreadLocal()
}

/**
 * Restores the coroutine's override context every time it resumes on a thread,
 * giving Kotlin the same overlapping-async isolation that Dart gets from Zones.
 */
private class ViewModelSpecOverrideContextElement(
    private val context: ViewModelSpecOverrideContext,
) : ThreadContextElement<ViewModelSpecOverrideContext?>,
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ViewModelSpecOverrideContextElement>

    override fun updateThreadContext(context: CoroutineContext): ViewModelSpecOverrideContext? {
        val previous = ViewModelSpecOverrideRuntime.current.get()
        ViewModelSpecOverrideRuntime.current.set(this.context)
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: ViewModelSpecOverrideContext?,
    ) {
        ViewModelSpecOverrideRuntime.current.set(oldState)
    }
}

/** Shared legacy/scoped proxy mechanics for every spec arity. */
internal class ViewModelSpecProxyState<Spec : Any> {
    private var legacyProxy: Spec? = null
    private val manualEntries = mutableListOf<ViewModelSpecOverrideEntry>()

    @Suppress("UNCHECKED_CAST")
    val activeProxy: Spec?
        get() = ViewModelSpecOverrideRuntime.current.get()?.activeSpec(this) as? Spec
            ?: manualEntries.lastOrNull()?.spec as? Spec
            ?: legacyProxy

    fun setProxy(spec: Spec) {
        legacyProxy = spec
    }

    fun clearProxy() {
        legacyProxy = null
    }

    fun overrideWith(spec: Spec): () -> Unit {
        assertMainThread()
        val entry = ViewModelSpecOverrideEntry(spec)
        val context = ViewModelSpecOverrideRuntime.current.get()
        if (context != null) {
            context.append(this, entry)
        } else {
            manualEntries += entry
        }

        var restored = false
        return {
            assertMainThread()
            if (!restored) {
                restored = true
                if (context != null) {
                    context.remove(this, entry)
                } else {
                    manualEntries.removeAll { it === entry }
                }
            }
        }
    }

    suspend fun <Result> runWithOverride(
        spec: Spec,
        block: suspend () -> Result,
    ): Result {
        assertMainThread()
        val context = ViewModelSpecOverrideContext(
            parent = ViewModelSpecOverrideRuntime.current.get(),
        )
        val entry = ViewModelSpecOverrideEntry(spec)
        context.append(this, entry)

        return withContext(ViewModelSpecOverrideContextElement(context)) {
            try {
                block()
            } finally {
                context.remove(this@ViewModelSpecProxyState, entry)
            }
        }
    }
}
