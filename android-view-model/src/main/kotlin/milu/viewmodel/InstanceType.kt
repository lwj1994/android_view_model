package milu.viewmodel

import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KType

/** Complete declared type identity, with a compatible path for non-generic KClass callers. */
@PublishedApi
internal class InstanceType<Value : Any>(
    val modelClass: KClass<Value>,
    val kotlinType: KType? = null,
) {
    init {
        if (kotlinType != null) {
            if (kotlinType.classifier != modelClass || kotlinType.isMarkedNullable) {
                throw ViewModelError("The declared type $kotlinType does not match ${modelClass.qualifiedName}.")
            }
        } else {
            val javaClass = modelClass.java
            if (javaClass.typeParameters.isNotEmpty()) {
                throw ViewModelError(
                    "A generic ViewModel type cannot be resolved from ${modelClass.qualifiedName}::class " +
                        "without its type arguments. Use viewModelSpec/viewModelSpecWithArg, " +
                        "a KType constructor, or TypedViewModelFactory with typeOf<VM>().",
                )
            }
            if (javaClass.enclosingClass != null && !Modifier.isStatic(javaClass.modifiers)) {
                throw ViewModelError(
                    "A non-static inner ViewModel requires a KType declaration: ${modelClass.qualifiedName}. " +
                        "Use viewModelSpec/viewModelSpecWithArg, a KType constructor, or " +
                        "TypedViewModelFactory with typeOf<VM>() to preserve its enclosing type.",
                )
            }
        }
    }

    // For a non-generic class, old KClass factories and reified Kotlin factories must agree.
    // Parameterized types retain the entire KType, including nested arguments and nullability.
    private val identity: Any = kotlinType?.takeIf { it.arguments.isNotEmpty() } ?: modelClass

    val description: String
        get() = kotlinType?.toString() ?: modelClass.qualifiedName ?: modelClass.java.name

    override fun equals(other: Any?): Boolean = other is InstanceType<*> && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()

    override fun toString(): String = description
}

internal fun <Value : Any> instanceTypeOf(type: KType): InstanceType<Value> {
    @Suppress("UNCHECKED_CAST")
    val modelClass = type.classifier as? KClass<Value>
        ?: throw ViewModelError("A concrete class type is required, but received $type.")
    return InstanceType(modelClass, type)
}

internal fun <VM : ViewModel> ViewModelFactory<VM>.instanceType(): InstanceType<VM> {
    if (this is ViewModelSpec<*>) {
        @Suppress("UNCHECKED_CAST")
        return instanceType as InstanceType<VM>
    }
    return InstanceType(modelClass, (this as? TypedViewModelFactory<*>)?.modelType)
}
