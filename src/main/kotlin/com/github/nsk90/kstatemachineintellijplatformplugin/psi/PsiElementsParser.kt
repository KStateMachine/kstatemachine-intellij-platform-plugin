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
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

private const val NAME_ARGUMENT = "name"
private const val STATE_ARGUMENT = "state"
private const val CHILD_MODE_ARGUMENT = "childMode"
private const val HISTORY_TYPE_ARGUMENT = "historyType"
private const val TARGET_STATE_PROPERTY = "targetState"
private const val GUARD_PROPERTY = "guard"

fun interface Output {
    fun write(message: String)
}

class PsiElementsParser(private val output: Output) {
    fun parse(psiFile: KtFile): List<StateMachine> {
        val pointerManager = SmartPointerManager.getInstance(psiFile.project)
        val machines = mutableListOf<StateMachine>()
        psiFile.findTopLevelMachineCalls().forEach { machineCall ->
            val machine = parseMachine(machineCall, pointerManager)
            machines += machine
            output.write("Parsed state machine: ${machine.name} (${machine.states.size} substates, ${machine.transitions.size} transitions)")
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
                    val calleeText = call.calleeExpression?.text
                    transitions += Transition(
                        name = transitionName,
                        pointer = pointerManager.createSmartPsiElementPointer(call),
                        targetStateName = call.findTargetStateName(),
                        eventType = call.typeArguments.firstOrNull()?.text,
                        callee = calleeText,
                        isGuarded = call.findLambdaAssignment(GUARD_PROPERTY) != null,
                        // dataTransition<E, D> / dataTransitionOn<E, D> — D is the 2nd type arg.
                        dataType = if (calleeText in DATA_TRANSITION_CALLEES)
                            call.typeArguments.getOrNull(1)?.text else null,
                    )
                }
                KStateMachineCalls.Kind.MACHINE -> {
                    // Nested machine — render it as a substate. StateMachine
                    // extends State so it slots into the children list directly,
                    // and the diagram generator wraps it in its own named block.
                    states += parseMachine(call, pointerManager)
                }
                null -> {
                    // Listener bodies (`onEntry`, `onTransitionComplete`, etc.)
                    // are runtime callbacks. They aren't DSL builders, and
                    // descending into them produces false positives — e.g. an
                    // MVI `state { copy(…) }` setter inside an `onTransitionComplete`
                    // body would match the catalog's "state" name and spawn a
                    // phantom node. Skip them.
                    val callee = call.calleeExpression?.text
                    if (callee != null && callee in KStateMachineCalls.LISTENER_FUNCTIONS) {
                        return@forEach
                    }
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
        val baseKind = call.kindFromCallee()
        // historyState defaults to SHALLOW; promote to HISTORY_DEEP when the
        // call explicitly passes `historyType = HistoryType.DEEP` (named or
        // positional).
        val kind = if (baseKind == StateKind.HISTORY && call.isDeepHistory()) StateKind.HISTORY_DEEP else baseKind
        return State(
            name = name,
            states = substates,
            transitions = transitions,
            pointer = pointerManager.createSmartPsiElementPointer(call),
            kind = kind,
            isParallel = call.isParallelChildMode(),
            // dataState<D> / initialDataState<D> / finalDataState<D> /
            // initialFinalDataState<D> / choiceDataState<D> /
            // initialChoiceDataState<D> — the D is the first (and only) type arg.
            dataType = if (kind.isData()) call.typeArguments.firstOrNull()?.text else null,
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
            isParallel = call.isParallelChildMode(),
        )
    }

    // createStateMachine / createStateMachineBlocking / createStdLibStateMachine —
    // builds a StateMachine (a State subclass) from the call's lambda. Used for
    // top-level machines AND nested machines (substates of another machine).
    private fun parseMachine(call: KtCallExpression, pointerManager: SmartPointerManager): StateMachine {
        // Machine factories — createStateMachine, createStateMachineBlocking,
        // createStdLibStateMachine, createTestStateMachine — all take the
        // CoroutineScope (or equivalent receiver/argument) as the FIRST
        // positional argument and `name` as the SECOND. We can't use the
        // generic "first positional" fallback here because that would pick up
        // the scope. Instead use a name-resolution path that requires either
        // a named `name = …` arg or a string-literal positional anywhere
        // (which finds "Hero" in `createStateMachine(scope, "Hero", …)`
        // without confusing it for the scope).
        val name = findMachineName(call) ?: "<unnamed>"
        val (states, transitions) = parseLambdaChildren(call.dslLambda(), pointerManager)
        return StateMachine(
            name = name,
            states = states,
            transitions = transitions,
            pointer = pointerManager.createSmartPsiElementPointer(call),
            isParallel = call.isParallelChildMode(),
        )
    }
}

private fun KtCallExpression.addStateKindFromCallee(): StateKind = when (calleeExpression?.text) {
    "addInitialState" -> StateKind.INITIAL
    "addFinalState" -> StateKind.FINAL
    else -> StateKind.STATE
}

// Returns only machine calls that are NOT nested inside another machine's
// lambda — nested ones get picked up as substates by parseMachine when we
// recurse into their parent. Returning them as top-level too would
// double-count them in the result.
private fun PsiElement.findTopLevelMachineCalls(): List<KtCallExpression> =
    PsiTreeUtil.findChildrenOfType(this, KtCallExpression::class.java)
        .filter { KStateMachineCalls.matchKind(it) == KStateMachineCalls.Kind.MACHINE }
        .filter { !it.isNestedInsideMachineCall() }

private fun KtCallExpression.isNestedInsideMachineCall(): Boolean {
    var ancestor: PsiElement? = parent
    while (ancestor != null) {
        if (ancestor is KtCallExpression
            && ancestor !== this
            && KStateMachineCalls.matchKind(ancestor) == KStateMachineCalls.Kind.MACHINE
        ) {
            return true
        }
        ancestor = ancestor.parent
    }
    return false
}

private val DATA_TRANSITION_CALLEES = setOf("dataTransition", "dataTransitionOn")

private fun StateKind.isData(): Boolean = when (this) {
    StateKind.DATA,
    StateKind.INITIAL_DATA,
    StateKind.FINAL_DATA,
    StateKind.INITIAL_FINAL_DATA,
    StateKind.MUTABLE_DATA,
    StateKind.INITIAL_MUTABLE_DATA,
    StateKind.FINAL_MUTABLE_DATA,
    StateKind.INITIAL_FINAL_MUTABLE_DATA,
    StateKind.CHOICE_DATA,
    StateKind.INITIAL_CHOICE_DATA -> true
    else -> false
}

private fun KtCallExpression.kindFromCallee(): StateKind = when (calleeExpression?.text) {
    "initialState" -> StateKind.INITIAL
    "finalState" -> StateKind.FINAL
    "initialFinalState" -> StateKind.INITIAL_FINAL
    "dataState" -> StateKind.DATA
    "initialDataState" -> StateKind.INITIAL_DATA
    "finalDataState" -> StateKind.FINAL_DATA
    "initialFinalDataState" -> StateKind.INITIAL_FINAL_DATA
    "mutableDataState" -> StateKind.MUTABLE_DATA
    "initialMutableDataState" -> StateKind.INITIAL_MUTABLE_DATA
    "finalMutableDataState" -> StateKind.FINAL_MUTABLE_DATA
    "initialFinalMutableDataState" -> StateKind.INITIAL_FINAL_MUTABLE_DATA
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
    return findLambdaAssignment(TARGET_STATE_PROPERTY)
}

/**
 * Walk the call's trailing-lambda body looking for `propertyName = <expr>`
 * (a simple-name assignment). Used for both `targetState = …` and `guard = …`.
 * Skips nested lambdas so we stay in the immediate transition scope.
 */
private fun KtCallExpression.findLambdaAssignment(propertyName: String): String? {
    val body = dslLambda()?.bodyExpression ?: return null
    var found: String? = null
    fun walk(element: PsiElement) {
        if (found != null) return
        for (child in element.children) {
            if (child is KtLambdaExpression) continue
            if (child is KtBinaryExpression
                && child.operationToken == KtTokens.EQ
                && child.left?.text == propertyName
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

/**
 * True if the call passes `HistoryType.DEEP` for the `historyType` parameter.
 * Accepts either a named arg (`historyState(historyType = HistoryType.DEEP)`)
 * or a positional `HistoryType.DEEP` / bare `DEEP` enum constant.
 */
private fun KtCallExpression.isDeepHistory(): Boolean {
    val args = valueArgumentList?.arguments.orEmpty()
    return args.any { arg ->
        val argName = arg.getArgumentName()?.asName?.asString()
        if (argName != null && argName != HISTORY_TYPE_ARGUMENT) return@any false
        val text = arg.getArgumentExpression()?.text?.trim() ?: return@any false
        text == "DEEP" || text.endsWith(".DEEP")
    }
}

/**
 * True if the call passes `ChildMode.PARALLEL` for the `childMode` parameter.
 *
 * Accepts either form:
 *   state("x", childMode = ChildMode.PARALLEL) { … }    // named
 *   createStateMachineBlocking(scope, "x", ChildMode.PARALLEL, …)  // positional
 *
 * For positional we accept any argument whose text ends in `.PARALLEL` or is
 * the bare `PARALLEL` enum constant — both unambiguous in KStateMachine
 * context since `PARALLEL` only appears on the `ChildMode` enum.
 */
private fun KtCallExpression.isParallelChildMode(): Boolean {
    val args = valueArgumentList?.arguments.orEmpty()
    return args.any { arg ->
        val argName = arg.getArgumentName()?.asName?.asString()
        // If the arg IS named but isn't `childMode`, skip.
        if (argName != null && argName != CHILD_MODE_ARGUMENT) return@any false
        val text = arg.getArgumentExpression()?.text?.trim() ?: return@any false
        text == "PARALLEL" || text.endsWith(".PARALLEL")
    }
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
 * PSI-only argument extractor. Returns a human-friendly string for
 * [argumentName]'s value in [callExpression], or null if not provided.
 *
 * Resolution order:
 * 1. Explicit named argument (`state(name = "red")`).
 * 2. For [NAME_ARGUMENT]: prefer the first **string-literal** positional arg —
 *    this avoids picking up `createStateMachineBlocking(scope, "Hero", …)`'s
 *    `scope` (1st positional) as the machine name when "Hero" (2nd) is what
 *    the user means.
 * 3. For [STATE_ARGUMENT]: take the first positional and simplify
 *    constructor-call expressions to their type name —
 *    `addState(AirAttacking())` → "AirAttacking", not "AirAttacking()".
 * 4. Generic fallback: text of the first positional argument.
 *
 * Going PSI-only intentionally avoids the K1 analysis API (deprecated in
 * modern Kotlin plugin builds). We can't resolve defaults; returning null is
 * fine because the renderers display "(Unnamed …)" for missing names.
 */
private fun findArgumentValueWithDefaults(
    callExpression: KtCallExpression,
    argumentName: String
): String? {
    val args = callExpression.valueArgumentList?.arguments.orEmpty()

    // (1) Explicit named argument.
    args.firstOrNull { it.getArgumentName()?.asName?.asString() == argumentName }
        ?.getArgumentExpression()
        ?.resolveAsLiteral()
        ?.let { return it }

    // (2) For machine / state / transition names, prefer the first positional
    // argument that resolves to a string literal — either directly or via a
    // same-file constant. This lets `createStateMachineBlocking(scope, "Hero", …)`
    // pick "Hero" (skipping `scope`), and also resolves
    // `state(RED_STATE_NAME)` where RED_STATE_NAME is a same-file constant.
    if (argumentName == NAME_ARGUMENT) {
        args.firstOrNull { arg ->
            arg.getArgumentName() == null && arg.getArgumentExpression()?.resolvesToStringLiteral() == true
        }?.getArgumentExpression()?.resolveAsLiteral()?.let { return it }
    }

    // (3) addState(MyState()) → "MyState" (drop the constructor parens).
    if (argumentName == STATE_ARGUMENT) {
        args.firstOrNull { it.getArgumentName() == null }
            ?.getArgumentExpression()
            ?.simplifiedStateName()
            ?.let { return it }
    }

    // (4) Generic positional fallback — resolve constants when possible.
    args.firstOrNull { it.getArgumentName() == null }
        ?.getArgumentExpression()
        ?.resolveAsLiteral()
        ?.let { return it }

    return null
}

// Constructor-call args like `AirAttacking()` reduce to their callee text
// "AirAttacking", which renders much more cleanly in the tree / diagram than
// the raw expression with its empty parens.
private fun KtExpression.simplifiedStateName(): String = when (this) {
    is KtCallExpression -> calleeExpression?.text ?: text
    else -> text
}

/**
 * Extract the `name` argument from a machine-creation call. Unlike the
 * general-purpose [findArgumentValueWithDefaults] this DOES NOT fall back to
 * the first positional argument — that slot is the scope (CoroutineScope or
 * similar receiver), never the name.
 */
private fun findMachineName(call: KtCallExpression): String? {
    val args = call.valueArgumentList?.arguments.orEmpty()

    // (1) Explicit `name = …` named argument.
    args.firstOrNull { it.getArgumentName()?.asName?.asString() == NAME_ARGUMENT }
        ?.getArgumentExpression()
        ?.resolveAsLiteral()
        ?.let { return it }

    // (2) First positional arg that resolves to a string literal — directly
    // or via a same-file constant. This is what makes
    // `createStateMachineBlocking(scope, "Hero", …)` work: "Hero" is the only
    // string-literal positional, so it wins.
    args.firstOrNull { arg ->
        arg.getArgumentName() == null && arg.getArgumentExpression()?.resolvesToStringLiteral() == true
    }?.getArgumentExpression()?.resolveAsLiteral()?.let { return it }

    return null
}

/**
 * Returns the expression's text, resolving same-file string constants when
 * possible. Handles:
 *   - String literals: returned as-is (with surrounding quotes preserved).
 *   - Local val/var: `val name = "red"` then `state(name)` in the same block
 *     resolves to "\"red\"". The closest enclosing scope wins (shadowing).
 *   - Top-level constants: `const val RED = "red"` → `state(RED)` resolves to "\"red\"".
 *   - Object members: `object Names { const val RED = "red" }` → `state(Names.RED)`.
 *   - Class companions: `class Names { companion object { const val RED = "red" } }`
 *     → `state(Names.RED)`.
 *
 * Falls back to raw expression text when no constant resolution applies. The
 * caller (renderer) strips surrounding quotes via `String.unquote()`, so the
 * final tree label shows `red` either way.
 */
private fun KtExpression.resolveAsLiteral(): String {
    if (this is KtStringTemplateExpression) return text
    val file = containingKtFile
    when (this) {
        is KtNameReferenceExpression -> {
            findLocalStringConstant()?.let { return it }
            file.findTopLevelStringConstant(text)?.let { return it }
        }
        is KtDotQualifiedExpression -> {
            val receiver = receiverExpression as? KtNameReferenceExpression
            val selector = selectorExpression as? KtNameReferenceExpression
            if (receiver != null && selector != null) {
                file.findScopedStringConstant(receiver.text, selector.text)?.let { return it }
            }
        }
    }
    return text
}

/** True if [resolveAsLiteral] would yield a string-literal value (directly or via constant). */
private fun KtExpression.resolvesToStringLiteral(): Boolean {
    if (this is KtStringTemplateExpression) return true
    val file = containingKtFile
    return when (this) {
        is KtNameReferenceExpression -> {
            findLocalStringConstant() != null || file.findTopLevelStringConstant(text) != null
        }
        is KtDotQualifiedExpression -> {
            val receiver = receiverExpression as? KtNameReferenceExpression ?: return false
            val selector = selectorExpression as? KtNameReferenceExpression ?: return false
            file.findScopedStringConstant(receiver.text, selector.text) != null
        }
        else -> false
    }
}

/**
 * Walks up the PSI from a name reference looking for an enclosing `KtBlockExpression`
 * (function body, lambda body, init block, etc.) that declares a `val name = "literal"`
 * (or `var`) matching this reference. Closest scope wins — that's standard Kotlin
 * lexical scoping with shadowing.
 *
 * Returns the literal's raw text (still quoted), or null if no scope has a matching
 * declaration with a string-literal initializer.
 */
private fun KtNameReferenceExpression.findLocalStringConstant(): String? {
    val targetName = text
    var element: PsiElement? = parent
    while (element != null) {
        if (element is KtBlockExpression) {
            for (stmt in element.statements) {
                if (stmt !is KtProperty || stmt.name != targetName) continue
                // Closest matching declaration wins — even if its initializer
                // isn't a string literal, anything in an outer scope is shadowed
                // and shouldn't be consulted.
                return (stmt.initializer as? KtStringTemplateExpression)?.text
            }
        }
        element = element.parent
    }
    return null
}

private fun KtFile.findTopLevelStringConstant(name: String): String? {
    val prop = declarations.filterIsInstance<KtProperty>().firstOrNull { it.name == name } ?: return null
    return (prop.initializer as? KtStringTemplateExpression)?.text
}

private fun KtFile.findScopedStringConstant(scope: String, member: String): String? {
    val container: org.jetbrains.kotlin.psi.KtClassOrObject =
        declarations.filterIsInstance<KtObjectDeclaration>().firstOrNull { it.name == scope }
            ?: declarations.filterIsInstance<KtClass>().firstOrNull { it.name == scope }
                ?.companionObjects?.firstOrNull()
            ?: return null
    val prop = container.declarations.filterIsInstance<KtProperty>().firstOrNull { it.name == member } ?: return null
    return (prop.initializer as? KtStringTemplateExpression)?.text
}
