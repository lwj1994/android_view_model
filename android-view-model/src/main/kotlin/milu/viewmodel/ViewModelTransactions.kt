package milu.viewmodel

import java.util.Collections
import java.util.IdentityHashMap
import kotlin.reflect.KClass

/** Parent/binding-private key used for the default unkeyed identity. */
internal class ViewModelPrivateKey

internal fun <T : Any> identitySet(): MutableSet<T> =
    Collections.newSetFromMap(IdentityHashMap())

private data class ConstructionIdentity(
    val type: KClass<*>,
    val key: Any,
    val implicit: Boolean,
) {
    val description: String
        get() = if (implicit) "${type.simpleName}(unkeyed)" else "${type.simpleName}($key)"
}

private class ConstructionTransaction {
    private val rollbacks = mutableListOf<() -> Unit>()
    private var completed = false

    fun register(rollback: () -> Unit) {
        if (!completed) rollbacks += rollback
    }

    fun commit() {
        if (completed) return
        completed = true
        rollbacks.clear()
    }

    fun rollback() {
        if (completed) return
        completed = true
        rollbacks.asReversed().forEach { rollback ->
            runCatching(rollback)
        }
        rollbacks.clear()
    }
}

private val activeConstructionLineage = mutableListOf<ConstructionIdentity>()
private var activeConstructionTransaction: ConstructionTransaction? = null

internal fun registerViewModelConstructionRollback(rollback: () -> Unit) {
    activeConstructionTransaction?.register(rollback)
}

internal fun <R> runInViewModelConstruction(
    type: KClass<*>,
    key: Any,
    isImplicit: Boolean,
    block: () -> R,
): R {
    val cycle = activeConstructionLineage.any { ancestor ->
        ancestor.type == type && if (isImplicit) true else !ancestor.implicit && ancestor.key == key
    }
    val current = ConstructionIdentity(type, key, isImplicit)
    if (cycle) {
        val path = (activeConstructionLineage + current).joinToString(" -> ") { it.description }
        throw ViewModelError(
            "Circular ViewModel construction detected: $path. " +
                "Unkeyed ViewModels are compared by type within the current construction lineage; " +
                "use explicit distinct keys only for intentional nesting.",
        )
    }

    val transaction = ConstructionTransaction()
    val previousTransaction = activeConstructionTransaction
    activeConstructionTransaction = transaction
    activeConstructionLineage += current
    return try {
        block().also { transaction.commit() }
    } catch (error: Throwable) {
        transaction.rollback()
        throw error
    } finally {
        activeConstructionLineage.removeAt(activeConstructionLineage.lastIndex)
        activeConstructionTransaction = previousTransaction
    }
}

private class UpdateTransaction {
    val updatedBindings: MutableSet<ViewModelBinding> = identitySet()
}

private var activeUpdateTransaction: UpdateTransaction? = null

/** Runs one synchronous notification graph in a shared de-duplication scope. */
internal fun <R> runInViewModelUpdateTransaction(block: () -> R): R {
    if (activeUpdateTransaction != null) return block()
    activeUpdateTransaction = UpdateTransaction()
    return try {
        block()
    } finally {
        activeUpdateTransaction = null
    }
}

internal fun markViewModelBindingUpdated(binding: ViewModelBinding): Boolean =
    activeUpdateTransaction?.updatedBindings?.add(binding) ?: true
