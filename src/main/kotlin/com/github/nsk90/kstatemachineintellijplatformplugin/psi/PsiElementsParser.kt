package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.github.nsk90.kstatemachineintellijplatformplugin.model.State
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.model.Transition
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.constant.ConstantValue
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.psi.KtFile

private data class Declaration(val name: String, val import: String) {
    val fullName get() = "$import.$name"
}

private val CREATE_STATE_MACHINE_FUNCTIONS = listOf(
    Declaration("createStateMachine", "ru.nsk.kstatemachine.coroutines"),
    Declaration("createStateMachineBlocking", "ru.nsk.kstatemachine.statemachine"),
    Declaration("createStdLibStateMachine", "ru.nsk.kstatemachine.statemachine"),
)

private val STATE_FACTORY_FUNCTIONS = listOf(
    Declaration("state", "ru.nsk.kstatemachine.state"),
    Declaration("dataState", "ru.nsk.kstatemachine.state"),
    Declaration("initialState", "ru.nsk.kstatemachine.state"),
    Declaration("initialDataState", "ru.nsk.kstatemachine.state"),
    Declaration("finalDataState", "ru.nsk.kstatemachine.state"),
    Declaration("initialFinalDataState", "ru.nsk.kstatemachine.state"),
    Declaration("choiceState", "ru.nsk.kstatemachine.state"),
    Declaration("initialChoiceState", "ru.nsk.kstatemachine.state"),
    Declaration("choiceDataState", "ru.nsk.kstatemachine.state"),
    Declaration("initialChoiceDataState", "ru.nsk.kstatemachine.state"),
    Declaration("historyState", "ru.nsk.kstatemachine.state"),
)

private val ADD_STATE_FUNCTIONS = listOf(
    Declaration("addState", "ru.nsk.kstatemachine.state.State"),
    Declaration("addState", "ru.nsk.kstatemachine.state"),
    Declaration("addInitialState", "ru.nsk.kstatemachine.state"),
    Declaration("addFinalState", "ru.nsk.kstatemachine.state"),
)

private val TRANSITION_FUNCTIONS = listOf(
    Declaration("transition", "ru.nsk.kstatemachine.state"),
    Declaration("transitionOn", "ru.nsk.kstatemachine.state"),
    Declaration("transitionConditionally", "ru.nsk.kstatemachine.state"),
    Declaration("dataTransition", "ru.nsk.kstatemachine.state"),
    Declaration("dataTransitionOn", "ru.nsk.kstatemachine.state"),
)

private const val NAME_ARGUMENT = "name"
private const val STATE_ARGUMENT = "state"

fun interface Output {
    fun write(message: String)
}

class PsiElementsParser(private val output: Output) {
    fun parse(psiFile: KtFile): List<StateMachine> {
        val machines = mutableListOf<StateMachine>()
        psiFile.findMethodCallsInElement(CREATE_STATE_MACHINE_FUNCTIONS).forEach { machineCall ->
            val name = findArgumentValueWithDefaults(machineCall, NAME_ARGUMENT) ?: "<unnamed>"
            val (states, transitions) = parseLambdaChildren(machineCall.dslLambda())
            val machine = StateMachine(name, states, transitions)
            machines += machine
            machine.print(0)
        }
        return machines
    }

    private fun parseLambdaChildren(
        lambda: KtLambdaExpression?,
    ): Pair<List<State>, List<Transition>> {
        if (lambda == null) return emptyList<State>() to emptyList()
        val states = mutableListOf<State>()
        val transitions = mutableListOf<Transition>()
        lambda.directCallExpressions().forEach { call ->
            when {
                call.matchesAny(STATE_FACTORY_FUNCTIONS) -> states += parseState(call)
                call.matchesAny(ADD_STATE_FUNCTIONS) -> {
                    val stateName = findArgumentValueWithDefaults(call, STATE_ARGUMENT) ?: "<unknown>"
                    states += State(stateName, emptyList(), emptyList())
                }
                call.matchesAny(TRANSITION_FUNCTIONS) -> {
                    val transitionName = findArgumentValueWithDefaults(call, NAME_ARGUMENT) ?: "<unnamed>"
                    transitions += Transition(transitionName)
                }
            }
        }
        return states to transitions
    }

    private fun parseState(call: KtCallExpression): State {
        val name = findArgumentValueWithDefaults(call, NAME_ARGUMENT) ?: "<unnamed>"
        val (substates, transitions) = parseLambdaChildren(call.dslLambda())
        return State(name, substates, transitions)
    }

    private fun State.print(level: Int) {
        val indent = "  ".repeat(level)
        output.write("$indent ${this::class.simpleName} name=$name")
        transitions.forEach {
            output.write("$indent ${it::class.simpleName} name=${it.name}")
        }
        states.forEach { it.print(level + 1) }
    }
}

private fun KtCallExpression.dslLambda(): KtLambdaExpression? =
    lambdaArguments.firstOrNull()?.getLambdaExpression()

// Returns every KtCallExpression reachable inside this lambda's body without
// descending into deeper lambdas — so each state's lambda forms its own scope.
private fun KtLambdaExpression.directCallExpressions(): List<KtCallExpression> {
    val body = bodyExpression ?: return emptyList()
    val result = mutableListOf<KtCallExpression>()
    fun walk(element: PsiElement) {
        for (child in element.children) {
            if (child is KtLambdaExpression) continue
            if (child is KtCallExpression) result += child
            walk(child)
        }
    }
    walk(body)
    return result
}

private fun KtCallExpression.matchesAny(declarations: List<Declaration>): Boolean {
    val calleeText = calleeExpression?.text ?: return false
    return declarations.any { it.name == calleeText && isExpectedDeclaration(this, it.fullName) }
}

private fun isExpectedDeclaration(callExpression: KtCallExpression, expectedFqName: String): Boolean {
    val context = callExpression.analyze()
    val resolvedCall = callExpression.getResolvedCall(context)
    val fqName = resolvedCall?.resultingDescriptor?.fqNameSafe?.asString()
    return fqName == expectedFqName
}

private fun PsiElement.findMethodCallsInElement(declarations: List<Declaration>): List<KtCallExpression> {
    return PsiTreeUtil.findChildrenOfType(this, KtCallExpression::class.java).mapNotNull { expression ->
        expression.takeIf {
            val matchingDeclaration = declarations.filter { it.name == expression.calleeExpression?.text }
            matchingDeclaration.find { isExpectedDeclaration(expression, it.fullName) } != null
        }
    }
}

private fun findArgumentValueWithDefaults(
    callExpression: KtCallExpression,
    argumentName: String
): String? {
    val context = callExpression.analyze()
    val resolvedCall = callExpression.getResolvedCall(context) ?: return null
    val parameterDescriptors = resolvedCall.resultingDescriptor.valueParameters

    for (parameter in parameterDescriptors) {
        if (parameter.name.asString() != argumentName) continue

        val resolvedArgument = resolvedCall.valueArguments[parameter]
        if (resolvedArgument != null) {
            val argumentExpression = resolvedArgument.arguments.firstOrNull()?.getArgumentExpression()
            return argumentExpression?.text ?: "null"
        }
        return getDefaultValue(parameter, context) ?: "null"
    }

    return null
}

private fun getDefaultValue(parameter: ValueParameterDescriptor, context: BindingContext): String? {
    if (!parameter.declaresDefaultValue()) return null

    val psiElement = parameter.source.getPsi() as? KtParameter ?: return null
    val defaultValueExpression = psiElement.defaultValue ?: return null

    val constantValue = ConstantExpressionEvaluator.getConstant(defaultValueExpression, context)
    return (constantValue as? ConstantValue<*>)?.value?.toString() ?: defaultValueExpression.text
}
