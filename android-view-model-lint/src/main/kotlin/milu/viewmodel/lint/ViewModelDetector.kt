package milu.viewmodel.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtConstructorDelegationCall
import org.jetbrains.uast.*

/** Usage diagnostics stay outside the Android runtime and never change ownership. */
class ViewModelDetector : Detector(), SourceCodeScanner {
    private data class Edge(
        val from: String,
        val to: String,
        val context: JavaContext,
        val call: UCallExpression,
    )

    private val edges = mutableListOf<Edge>()

    override fun beforeCheckEachProject(context: Context) {
        edges.clear()
    }

    override fun getApplicableUastTypes() = listOf(UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext) = object : UElementHandler() {
        override fun visitCallExpression(node: UCallExpression) {
            val method = node.resolve() ?: return
            if (isSpec(method)) {
                checkSpec(context, node, method)
            } else if (node.kind == UastCallKind.CONSTRUCTOR_CALL &&
                isViewModel(context, method.containingClass) &&
                node.sourcePsi !is KtSuperTypeCallEntry &&
                node.sourcePsi !is KtConstructorDelegationCall &&
                node.methodName !in setOf("super", "this") && !isManaged(context, node)
            ) {
                context.report(
                    UNMANAGED, node, context.getLocation(node),
                    "Construct ViewModels in a spec builder or ViewModelFactory.build; resolve the spec through an owner binding.",
                )
            }

            if (!isResolution(context, node, method)) return
            if (isViewModel(context, (node.getExpressionType() as? PsiClassType)?.resolve()) && isStored(node)) {
                context.report(
                    CACHED, node, context.getLocation(node),
                    "A stored ViewModel can remain stale after recycle; use by readViewModel/watchViewModel with the owner's binding.",
                )
            }
            val owner = node.getContainingUClass()?.javaPsi ?: return
            if (!isViewModel(context, owner) || !usesOwnBinding(node, method)) return
            val target = resolvedViewModel(context, node.getExpressionType()) ?: return
            val from = owner.qualifiedName ?: return
            val to = target.qualifiedName ?: return
            edges.add(Edge(from, to, context, node))
        }
    }

    private fun isFramework(method: PsiMethod): Boolean =
        method.containingClass?.qualifiedName?.substringBeforeLast('.') == "milu.viewmodel"

    private fun isSpec(method: PsiMethod): Boolean {
        if (!isFramework(method)) return false
        val name = if (method.isConstructor) method.containingClass?.name else method.name
        return name in specNames
    }

    private fun argument(call: UCallExpression, method: PsiMethod, name: String): UExpression? {
        val index = method.parameterList.parameters.indexOfFirst { it.name == name }
        return if (index < 0) null else call.getArgumentForParameter(index)
    }

    private fun literal(expression: UExpression?): UExpression? = when (expression) {
        is UParenthesizedExpression -> literal(expression.expression)
        is ULambdaExpression -> literal(expression.body)
        is UBlockExpression -> expression.expressions.singleOrNull()?.let(::literal)
        is UReturnExpression -> literal(expression.returnExpression)
        else -> expression
    }

    private fun checkSpec(context: JavaContext, call: UCallExpression, method: PsiMethod) {
        val key = argument(call, method, "key")
        val missingKey = key == null || (literal(key) as? ULiteralExpression)?.isNull == true
        if (!missingKey) return
        if (literal(argument(call, method, "aliveForever"))?.evaluate() == true) {
            context.report(
                ALIVE_KEY, call, context.getLocation(call),
                "aliveForever is true without an explicit non-null key; managed resolution requires a key.",
            )
        }
    }

    private fun isViewModel(context: JavaContext, type: PsiClass?): Boolean = type != null &&
        context.evaluator.extendsClass(type, "milu.viewmodel.ViewModel", false)

    private fun resolvedViewModel(context: JavaContext, type: PsiType?): PsiClass? {
        val classType = type as? PsiClassType ?: return null
        val resolved = classType.resolve()
        if (isViewModel(context, resolved)) return resolved
        // Property delegates keep the VM as their first type parameter.
        if (resolved?.qualifiedName == "milu.viewmodel.ViewModelDelegate") {
            return resolvedViewModel(context, classType.parameters.firstOrNull())
        }
        return null
    }

    private fun isResolution(context: JavaContext, call: UCallExpression, method: PsiMethod): Boolean {
        if (!isFramework(method) || method.name !in resolutionNames) return false
        return resolvedViewModel(context, call.getExpressionType()) != null
    }

    private fun usesOwnBinding(call: UCallExpression, method: PsiMethod): Boolean {
        fun own(expression: UExpression?): Boolean {
            val value = literal(expression) ?: return false
            val target = value.tryResolve() as? PsiMethod ?: return false
            if (value is UQualifiedReferenceExpression && value.receiver !is UThisExpression) return false
            return target.name == "getViewModelBinding" &&
                target.containingClass?.qualifiedName == "milu.viewmodel.ViewModel"
        }
        if (method.name in delegateNames) {
            return own(argument(call, method, "binding")) || own(call.receiver)
        }
        return own(call.receiver)
    }

    private fun isManaged(context: JavaContext, call: UCallExpression): Boolean {
        var child: UElement = call
        var parent = child.uastParent
        while (parent != null) {
            if (parent is ULambdaExpression) {
                var container = parent.uastParent
                if (container is UNamedExpression) container = container.uastParent
                if (container is UCallExpression) {
                    val method = container.resolve()
                    if (method != null && isSpec(method) &&
                        argument(container, method, "builder")?.sourcePsi == parent.sourcePsi
                    ) return true
                }
            }
            if (parent is UMethod) {
                return parent.name == "build" && parent.uastParameters.isEmpty() &&
                    !parent.isStatic && context.evaluator.implementsInterface(
                        parent.javaPsi.containingClass ?: return false, "milu.viewmodel.ViewModelFactory", false,
                    )
            }
            if (parent is UClass) return false
            child = parent
            parent = child.uastParent
        }
        return false
    }

    private fun isStored(call: UCallExpression): Boolean {
        var child: UElement = call
        while (true) {
            val parent = child.uastParent ?: return false
            when (parent) {
                is UParenthesizedExpression, is UNamedExpression -> child = parent
                is UQualifiedReferenceExpression -> {
                    if (parent.selector != child) return false
                    child = parent
                }
                is UField -> {
                    // A readViewModel delegate stores a resolver, not a resolved VM.
                    val property = parent.sourcePsi as? KtProperty
                    return property?.hasDelegate() != true || call.methodName !in delegateNames
                }
                is UBinaryExpression -> {
                    if (parent.operator != UastBinaryOperator.ASSIGN || parent.rightOperand != child) return false
                    val target = parent.leftOperand.tryResolve()
                    if (target is com.intellij.psi.PsiField) return true
                    val property = target?.navigationElement as? KtProperty
                    return property != null && property.isVar && property.setter?.hasBody() != true
                }
                is UReturnExpression -> {
                    if (parent.returnExpression != child) return false
                    child = parent
                }
                is UBlockExpression -> {
                    if (parent.expressions.lastOrNull() != child) return false
                    child = parent
                }
                // Only a VM returned by a field's lazy initializer is retained.
                // Side-effect reads and locally scoped lazy variables are safe.
                is ULambdaExpression -> {
                    if (parent.body != child) return false
                    var lazyCall = parent.uastParent
                    if (lazyCall is UNamedExpression) lazyCall = lazyCall.uastParent
                    if (lazyCall !is UCallExpression || lazyCall.methodName != "lazy" ||
                        lazyCall.resolve()?.containingClass?.qualifiedName?.startsWith("kotlin.") != true
                    ) return false
                    return isStored(lazyCall)
                }
                else -> return false
            }
        }
    }

    override fun afterCheckEachProject(context: Context) {
        val outgoing = edges.groupBy { it.from }
        for (edge in edges.distinctBy { Triple(it.from, it.to, it.call.sourcePsi) }) {
            val seen = mutableSetOf<String>()
            fun path(from: String): List<String>? {
                if (from == edge.from) return listOf(from)
                if (!seen.add(from)) return null
                for (next in outgoing[from].orEmpty()) {
                    val tail = path(next.to)
                    if (tail != null) return listOf(from) + tail
                }
                return null
            }
            val cycle = path(edge.to) ?: continue
            edge.context.report(
                CYCLE, edge.call, edge.context.getLocation(edge.call),
                "Potential ViewModel type dependency cycle: ${(listOf(edge.from) + cycle).joinToString(" -> ")}. Keys and bindings may distinguish instances; runtime cycle validation still applies.",
            )
        }
        edges.clear()
    }

    companion object {
        private val specNames = setOf("viewModelSpec", "ViewModelSpec") +
            (1..4).flatMap { n ->
                val suffix = if (n == 1) "" else "$n"
                listOf("viewModelSpecWithArg$suffix", "ViewModelSpecWithArg$suffix")
            }
        private val delegateNames = setOf("readViewModel", "watchViewModel")
        private val resolutionNames = delegateNames + setOf("read", "watch")
        private val implementation = Implementation(ViewModelDetector::class.java, Scope.JAVA_FILE_SCOPE)

        private fun issue(id: String, brief: String, explanation: String) = Issue.create(
            id, brief, explanation, Category.CORRECTNESS, 6, Severity.WARNING, implementation,
        )

        val CYCLE = issue("ViewModelDependencyCycle", "Potential ViewModel dependency cycle",
            "Statically visible read/watch and delegate edges form a type cycle. This is advisory: keys and bindings determine runtime instance identity.")
        val CACHED = issue("ViewModelCachedInstance", "Stored resolved ViewModel",
            "A field or lazy property holding a resolved VM can retain a disposed generation. Resolve through a binding-aware property delegate on each access.")
        val ALIVE_KEY = issue("ViewModelAliveForeverKey", "Retained spec requires a key",
            "An aliveForever spec must have an explicit non-null key before its builder can execute.")
        val UNMANAGED = issue("ViewModelUnmanagedConstruction", "ViewModel constructed outside a managed builder",
            "Construct managed ViewModels only in spec builders or ViewModelFactory.build, then resolve the spec with an owner binding.")
        val issues = listOf(CYCLE, CACHED, ALIVE_KEY, UNMANAGED)
    }
}
