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
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.source.getPsi
import kotlin.takeIf
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
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
        val bindingContext = psiFile.analyze()
        buildStateMachinesTree(psiFile, bindingContext)

        // build psi tree for dsl statemachine structure
        val stateMachines = mutableListOf<StateMachine>()
        // todo support nested machines
        psiFile.findMethodCallsInElement(CREATE_STATE_MACHINE_FUNCTIONS).forEach { stateMachineExpression ->
            output.write("Found method call: ${stateMachineExpression.calleeExpression?.text}")
            val nameArgument = requireNotNull(findArgumentValueWithDefaults(stateMachineExpression, NAME_ARGUMENT)) {
                "No state machine Name argument found"
            }
            // should go as deep as possible, and protect from duplicates
            val states = mutableListOf<State>()
            stateMachineExpression.findMethodCallsInElement(STATE_FACTORY_FUNCTIONS).forEach { stateExpression ->
                val nameArgument = requireNotNull(findArgumentValueWithDefaults(stateExpression, NAME_ARGUMENT)) {
                    "No state's Name argument found for code: ${stateExpression.calleeExpression?.text}"
                }
                output.write("Found method call: ${stateExpression.calleeExpression?.text} $nameArgument")
                states += State(nameArgument, emptyList(), emptyList())
            }
            stateMachineExpression.findMethodCallsInElement(ADD_STATE_FUNCTIONS).forEach { stateExpression ->
                val stateArgument = requireNotNull(findArgumentValueWithDefaults(stateExpression, STATE_ARGUMENT)) {
                    "No State argument found for code: ${stateExpression.calleeExpression?.text}"
                }
                output.write("Found method call: ${stateExpression.calleeExpression?.text} $stateArgument")
                states += State(stateArgument, emptyList(), emptyList())
            }

            val transitions = mutableListOf<Transition>()
            stateMachineExpression.findMethodCallsInElement(TRANSITION_FUNCTIONS).forEach { transitionExpression ->
                val nameArgument = requireNotNull(findArgumentValueWithDefaults(transitionExpression, NAME_ARGUMENT)) {
                    "No transition Name argument found for code: ${transitionExpression.calleeExpression?.text}"
                }
                output.write("Found method call: ${transitionExpression.calleeExpression?.text} $nameArgument")
                transitions += Transition(nameArgument)
            }
            stateMachines += StateMachine(nameArgument, states, transitions)
        }
        return stateMachines
    }
}

private fun isExpectedDeclaration(callExpression: KtCallExpression, expectedFqName: String): Boolean {
    // Resolve the function reference
    val context = callExpression.analyze() // Analyze the file to get the binding context
    val resolvedCall = callExpression.getResolvedCall(context)

    // Get the fully qualified name of the resolved function
    val fqName = resolvedCall?.resultingDescriptor?.fqNameSafe?.asString()
    // Compare with the expected fully qualified name
    return fqName == expectedFqName
}

private fun PsiElement.findMethodCallsInElement(declarations: List<Declaration>): List<KtCallExpression> {
    return PsiTreeUtil.findChildrenOfType(this, KtCallExpression::class.java).mapNotNull {
        it.takeIf { expression ->
            val matchingDeclaration = declarations.filter { it.name == expression.calleeExpression?.text }
            matchingDeclaration.find { isExpectedDeclaration(expression, it.fullName) } != null
        }
    }
}

private fun findArgumentValueWithDefaults(
    callExpression: KtCallExpression,
    argumentName: String
): String? {
    // Resolve the function being called
    val context = callExpression.analyze()
    val resolvedCall = callExpression.getResolvedCall(context) ?: return null
    val parameterDescriptors = resolvedCall.resultingDescriptor.valueParameters

    // Match provided arguments to parameters
    for ((index, parameter) in parameterDescriptors.withIndex()) {
        if (parameter.name.asString() != argumentName) continue

        // Get the argument mapped to this parameter
        val resolvedArgument = resolvedCall.valueArguments[parameter]

        // Check if the argument is explicitly provided
        if (resolvedArgument != null) {
            // Extract the first resolved value (Kotlin allows multiple values in certain cases)
            val argumentExpression = resolvedArgument.arguments.firstOrNull()?.getArgumentExpression()
            return argumentExpression?.text ?: "null"
        }

        // If the argument is not explicitly provided, check for a default value
        return getDefaultValue(parameter, context) ?: "null"
    }

    // Return null if no matching argument is found
    return null
}

private fun getDefaultValue(parameter: ValueParameterDescriptor, context: BindingContext): String? {
    // Check if the parameter declares a default value
    if (!parameter.declaresDefaultValue()) return null

    // Retrieve the default value expression from the PSI
    val psiElement = parameter.source.getPsi() as? KtParameter ?: return null
    val defaultValueExpression = psiElement.defaultValue ?: return null

    // Optionally evaluate the constant value
    val constantValue = ConstantExpressionEvaluator.getConstant(defaultValueExpression, context)
    return (constantValue as? ConstantValue<*>)?.value?.toString() ?: defaultValueExpression.text
}

private fun buildStateMachinesTree(psiFile: KtFile, bindingContext: BindingContext): List<StateMachine> {
    val stateMachines = mutableListOf<StateMachine>()
    psiFile.findMethodCallsInElement(CREATE_STATE_MACHINE_FUNCTIONS).forEach { stateMachineExpression ->
        processStateMachineCall(stateMachineExpression, bindingContext)?.let {
            stateMachines += it
        }
    }
    return stateMachines
}

private fun processStateMachineCall(
    stateMachineExpression: KtCallExpression,
    bindingContext: BindingContext
): StateMachine? {
    // Resolve the call to ensure it's the correct 'state' function
    val resolvedCall = stateMachineExpression.getResolvedCall(bindingContext) ?: return null
    val functionDescriptor = resolvedCall.resultingDescriptor as? FunctionDescriptor ?: return null

    // Verify the fully-qualified name of the 'state()' function
    if (functionDescriptor.fqNameSafe.asString() != "State.state") return null

    val nameArgument = requireNotNull(findArgumentValueWithDefaults(stateMachineExpression, NAME_ARGUMENT)) {
        "No state machine Name argument found"
    }

    // Handle the lambda block (implicit receiver)
    val lambdaArgument = stateMachineExpression.lambdaArguments.firstOrNull()?.getLambdaExpression()
    val nestedStates = mutableListOf<State>()
    // Process nested 'state()' calls in the lambda block
    lambdaArgument?.bodyExpression?.statements?.forEach { statement ->
        if (statement is KtCallExpression) {
            processStateMachineCall(statement, bindingContext)?.let { nestedStates.add(it) }
        }
    }

    val transitions = mutableListOf<Transition>()

    // Create and return the State object
    return StateMachine(nameArgument, nestedStates, transitions)
}