package com.github.nsk90.kstatemachineintellijplatformplugin.psi

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

private val NAME_ARGUMENT = "name"

private val stateFunctions = stateFactoryFunctions + addStateFunctions


fun interface Output {
    fun write(message: String)
}

class PsiElementsParser(private val output: Output) {
    fun parse(psiFile: PsiFile) {
        // build psi tree for dsl statemachine structure
        findMethodCallsInElement(psiFile, createStateMachineFunctions).forEach {
            output.write("Found method call: ${it.calleeExpression?.text}")
            // should go as deep as possible, and protect from duplicates
            findMethodCallsInElement(it, stateFunctions).forEach {
                val argument = findArgumentValueWithDefaults(it, NAME_ARGUMENT)
                val message = if (argument != null)
                    argument
                else
                    "No argument found"

                output.write("Found method call: ${it.calleeExpression?.text} $message")
            }
            findMethodCallsInElement(it, transitionFunctions).forEach {
                val argument = findArgumentValueWithDefaults(it, NAME_ARGUMENT)
                val message = if (argument != null)
                    argument
                else
                    "No argument found"
                output.write("Found method call: ${it.calleeExpression?.text} $message")
            }
        }
    }

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