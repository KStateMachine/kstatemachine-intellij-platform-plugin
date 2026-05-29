package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.github.nsk90.kstatemachineintellijplatformplugin.model.State
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateKind
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.model.Transition
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.constant.ConstantValue
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.source.getPsi

private const val NAME_ARGUMENT = "name"
private const val STATE_ARGUMENT = "state"
private const val TARGET_STATE_PROPERTY = "targetState"

fun interface Output {
    fun write(message: String)
}

class PsiElementsParser(private val output: Output) {
    fun parse(psiFile: KtFile): List<StateMachine> {
        val pointerManager = SmartPointerManager.getInstance(psiFile.project)
        val machines = mutableListOf<StateMachine>()
        psiFile.findMachineCalls().forEach { machineCall ->
            val name = findArgumentValueWithDefaults(machineCall, NAME_ARGUMENT) ?: "<unnamed>"
            val (states, transitions) = parseLambdaChildren(machineCall.dslLambda(), pointerManager)
            val machine = StateMachine(
                name = name,
                states = states,
                transitions = transitions,
                pointer = pointerManager.createSmartPsiElementPointer(machineCall),
            )
            machines += machine
            output.write("Parsed state machine: $name (${states.size} substates, ${transitions.size} transitions)")
        }
        return machines
    }

    private fun parseLambdaChildren(
        lambda: KtLambdaExpression?,
        pointerManager: SmartPointerManager,
    ): Pair<List<State>, List<Transition>> {
        if (lambda == null) return emptyList<State>() to emptyList()
        val states = mutableListOf<State>()
        val transitions = mutableListOf<Transition>()
        lambda.directCallExpressions().forEach { call ->
            when (KStateMachineCalls.matchKind(call)) {
                KStateMachineCalls.Kind.STATE -> states += parseState(call, pointerManager)
                KStateMachineCalls.Kind.ADD_STATE -> {
                    val stateName = findArgumentValueWithDefaults(call, STATE_ARGUMENT) ?: "<unknown>"
                    states += State(
                        name = stateName,
                        states = emptyList(),
                        transitions = emptyList(),
                        pointer = pointerManager.createSmartPsiElementPointer(call),
                    )
                }
                KStateMachineCalls.Kind.TRANSITION -> {
                    val transitionName = findArgumentValueWithDefaults(call, NAME_ARGUMENT) ?: "<unnamed>"
                    transitions += Transition(
                        name = transitionName,
                        pointer = pointerManager.createSmartPsiElementPointer(call),
                        targetStateName = call.findTargetStateName(),
                        eventType = call.typeArguments.firstOrNull()?.text,
                    )
                }
                KStateMachineCalls.Kind.MACHINE, null -> {
                    // Nested machine declarations would be promoted to top-level on next pass.
                    // No-op here so we don't double-count.
                }
            }
        }
        return states to transitions
    }

    private fun parseState(call: KtCallExpression, pointerManager: SmartPointerManager): State {
        val name = findArgumentValueWithDefaults(call, NAME_ARGUMENT) ?: "<unnamed>"
        val (substates, transitions) = parseLambdaChildren(call.dslLambda(), pointerManager)
        return State(
            name = name,
            states = substates,
            transitions = transitions,
            pointer = pointerManager.createSmartPsiElementPointer(call),
            kind = call.kindFromCallee(),
        )
    }
}

private fun PsiElement.findMachineCalls(): List<KtCallExpression> =
    PsiTreeUtil.findChildrenOfType(this, KtCallExpression::class.java)
        .filter { KStateMachineCalls.matchKind(it) == KStateMachineCalls.Kind.MACHINE }

private fun KtCallExpression.kindFromCallee(): StateKind = when (calleeExpression?.text) {
    "initialState" -> StateKind.INITIAL
    "finalState" -> StateKind.FINAL
    "initialFinalState" -> StateKind.INITIAL_FINAL
    "dataState" -> StateKind.DATA
    "initialDataState" -> StateKind.INITIAL_DATA
    "finalDataState" -> StateKind.FINAL_DATA
    "initialFinalDataState" -> StateKind.INITIAL_FINAL_DATA
    "choiceState" -> StateKind.CHOICE
    "initialChoiceState" -> StateKind.INITIAL_CHOICE
    "choiceDataState" -> StateKind.CHOICE_DATA
    "initialChoiceDataState" -> StateKind.INITIAL_CHOICE_DATA
    "historyState" -> StateKind.HISTORY
    else -> StateKind.STATE
}

// Best-effort: find the right-hand side of `targetState = ...` inside the
// transition's lambda body. Returns the raw text of the RHS (e.g. "redState"
// or "{ yellowState }" for transitionOn). null if not set.
private fun KtCallExpression.findTargetStateName(): String? {
    val body = dslLambda()?.bodyExpression ?: return null
    var found: String? = null
    fun walk(element: PsiElement) {
        if (found != null) return
        for (child in element.children) {
            if (child is KtLambdaExpression) continue
            if (child is KtBinaryExpression
                && child.operationToken == KtTokens.EQ
                && child.left?.text == TARGET_STATE_PROPERTY
            ) {
                found = child.right?.text
                return
            }
            walk(child)
        }
    }
    walk(body)
    return found
}

internal fun KtCallExpression.dslLambda(): KtLambdaExpression? =
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
