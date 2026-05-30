package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.github.nsk90.kstatemachineintellijplatformplugin.model.State
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateKind
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.model.Transition
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression

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
                KStateMachineCalls.Kind.ADD_STATE -> states += parseAddState(call, pointerManager)
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
                    // Unrecognized callee with a lambda is most likely the
                    // `state.invoke { … }` operator-call pattern that the DSL
                    // uses to configure a state held in a variable
                    // (e.g. `airAttacking { transition<E>(…) }`). Recurse into
                    // its lambda and attribute any found content to the
                    // current scope — without binding-context resolution we
                    // can't link the variable back to its `addState(…)` node,
                    // but at least the transitions appear somewhere visible.
                    val (nestedStates, nestedTransitions) =
                        parseLambdaChildren(call.dslLambda(), pointerManager)
                    states += nestedStates
                    transitions += nestedTransitions
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

    // addState / addInitialState / addFinalState take the state instance as the
    // first positional argument and accept a configuration lambda that holds
    // nested transitions and substates. We must recurse into the lambda — the
    // previous implementation skipped it and silently dropped every transition
    // declared this way.
    private fun parseAddState(call: KtCallExpression, pointerManager: SmartPointerManager): State {
        val name = findArgumentValueWithDefaults(call, STATE_ARGUMENT) ?: "<unknown>"
        val (substates, transitions) = parseLambdaChildren(call.dslLambda(), pointerManager)
        return State(
            name = name,
            states = substates,
            transitions = transitions,
            pointer = pointerManager.createSmartPsiElementPointer(call),
            kind = call.addStateKindFromCallee(),
        )
    }
}

private fun KtCallExpression.addStateKindFromCallee(): StateKind = when (calleeExpression?.text) {
    "addInitialState" -> StateKind.INITIAL
    "addFinalState" -> StateKind.FINAL
    else -> StateKind.STATE
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

// Best-effort: find the right-hand side of `targetState = …`. KStateMachine
// supports two forms:
//   transition<E>("name", targetState = Jumping)      // value argument
//   transition<E>("name") { targetState = X }          // lambda body assignment
// Returns the raw text of the target (e.g. "Jumping" or "{ X }" for the
// transitionOn lambda form). null if not set.
private fun KtCallExpression.findTargetStateName(): String? {
    // (1) Named value argument: `transition<E>(targetState = Foo)`
    valueArgumentList?.arguments
        ?.firstOrNull { it.getArgumentName()?.asName?.asString() == TARGET_STATE_PROPERTY }
        ?.getArgumentExpression()?.text
        ?.let { return it }

    // (2) Assignment inside the lambda body: `transition<E> { targetState = Foo }`
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

/**
 * PSI-only argument extractor. Returns the raw text of [argumentName]'s value
 * in [callExpression], or null if the argument isn't explicitly passed.
 *
 * Matches by named argument first (e.g. `state(name = "red")`). If [argumentName]
 * is "name" we additionally accept the first positional argument — the KStateMachine
 * DSL convention is `state("red")` without naming the parameter explicitly.
 *
 * We don't try to resolve default values here. Going PSI-only intentionally
 * avoids the K1 analysis API (deprecated in modern Kotlin plugin builds) and is
 * good enough because every KStateMachine state/transition factory either gives
 * the value inline or leaves it to default to `null`, which our renderer
 * displays as "(unnamed)".
 */
private fun findArgumentValueWithDefaults(
    callExpression: KtCallExpression,
    argumentName: String
): String? {
    val args = callExpression.valueArgumentList?.arguments.orEmpty()
    args.firstOrNull { it.getArgumentName()?.asName?.asString() == argumentName }
        ?.getArgumentExpression()?.text
        ?.let { return it }

    // Fallback to the first positional arg. KStateMachine DSL convention puts
    // the "primary" parameter first: state("red") / addState(myState) /
    // transition<E>("name") — so this lookup is right for all our call sites.
    args.firstOrNull { it.getArgumentName() == null }
        ?.getArgumentExpression()?.text
        ?.let { return it }

    return null
}
