package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.github.nsk90.kstatemachineintellijplatformplugin.model.State
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.model.Transition
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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
import kotlin.collections.contains
import kotlin.takeIf

private val createStateMachineFunctions = listOf(
    "createStateMachine",
    "createStateMachineBlocking",
    "createStdLibStateMachine",
)

private val stateFactoryFunctions = listOf(
    "state",
    "dataState",
    "initialState",
    "initialDataState",
    "finalDataState",
    "initialFinalDataState",
    "choiceState",
    "initialChoiceState",
    "initialChoiceState",
    "choiceDataState",
    "initialChoiceDataState",
    "historyState",
)

private val addStateFunctions = listOf(
    "addState",
    "addInitialState",
    "addFinalState",
)

private val transitionFunctions = listOf(
    "transition",
    "transitionOn",
    "transitionConditionally",
    "dataTransition",
    "dataTransitionOn",
)

private const val NAME_ARGUMENT = "name"

private val stateFunctions = stateFactoryFunctions + addStateFunctions

fun interface Output {
    fun write(message: String)
}

class PsiElementsParser(private val output: Output) {
    fun parse(psiFile: PsiFile): List<StateMachine> {
        // build psi tree for dsl statemachine structure
        val stateMachines = mutableListOf<StateMachine>()
        // todo support nested machines
        findMethodCallsInElement(psiFile, createStateMachineFunctions).forEach { stateMachineExpression ->
            output.write("Found method call: ${stateMachineExpression.calleeExpression?.text}")
            val nameArgument = requireNotNull(findArgumentValueWithDefaults(stateMachineExpression, NAME_ARGUMENT)) {
                "No state machine Name argument found"
            }
            // should go as deep as possible, and protect from duplicates
            val states = mutableListOf<State>()
            findMethodCallsInElement(stateMachineExpression, stateFunctions).forEach { stateExpression ->
                val nameArgument = requireNotNull(findArgumentValueWithDefaults(stateExpression, NAME_ARGUMENT)) {
                    "No state Name argument found"
                }
                output.write("Found method call: ${stateExpression.calleeExpression?.text} $nameArgument")
                states += State(nameArgument, emptyList(), emptyList())
            }
            val transitions = mutableListOf<Transition>()
            findMethodCallsInElement(stateMachineExpression, transitionFunctions).forEach { transitionExpression ->
                val nameArgument = requireNotNull(findArgumentValueWithDefaults(transitionExpression, NAME_ARGUMENT)) {
                    "No transition Name argument found"
                }
                output.write("Found method call: ${transitionExpression.calleeExpression?.text} $nameArgument")
                transitions += Transition(nameArgument)
            }
            stateMachines += StateMachine(nameArgument, states, transitions)
        }
        return stateMachines
    }

    /**
     * todo add imports validation
     */
    private fun findMethodCallsInElement(element: PsiElement, names: List<String>): List<KtCallExpression> {
        return PsiTreeUtil.findChildrenOfType(element, KtCallExpression::class.java).mapNotNull {
            it.takeIf { names.contains(it.calleeExpression?.text) }
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
}