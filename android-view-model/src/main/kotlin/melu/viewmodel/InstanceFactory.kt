package melu.viewmodel

internal data class InstanceFactory<Value : Any>(
    val builder: (() -> Value)? = null,
    val arg: InstanceArg = InstanceArg(),
) {
    val isEmpty: Boolean
        get() = builder == null && arg.key == null
}
