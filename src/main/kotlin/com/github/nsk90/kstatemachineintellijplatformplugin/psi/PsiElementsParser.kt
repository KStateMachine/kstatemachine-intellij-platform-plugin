package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.github.nsk90.kstatemachineintellijplatformplugin.model.Guard
import com.github.nsk90.kstatemachineintellijplatformplugin.model.State
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateKind
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.model.TargetGroup
import com.github.nsk90.kstatemachineintellijplatformplugin.model.Transition
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtObjectLiteralExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtSuperTypeCallEntry
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtWhenExpression

private const val NAME_ARGUMENT = "name"
private const val STATE_ARGUMENT = "state"
private const val CHILD_MODE_ARGUMENT = "childMode"
private const val HISTORY_TYPE_ARGUMENT = "historyType"
private const val DEFAULT_DATA_ARGUMENT = "defaultData"
private const val TARGET_STATE_PROPERTY = "targetState"
private const val GUARD_PROPERTY = "guard"
private const val DIRECTION_PROPERTY = "direction"
private const val TRANSITION_CONDITIONALLY_CALLEE = "transitionConditionally"
private val JOIN_TRANSITION_CALLEES = setOf("joinTransition", "joinDataTransition")
private const val TARGET_STATE_CALL = "targetState"
private const val TARGET_PARALLEL_STATES_CALL = "targetParallelStates"
private const val STAY_CALL = "stay"
private const val NO_TRANSITION_CALL = "noTransition"

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
        extraLambdas: List<KtLambdaExpression> = emptyList(),
        extraCalls: List<KtCallExpression> = emptyList(),
    ): Pair<List<State>, List<Transition>> {
        if (lambda == null && extraLambdas.isEmpty() && extraCalls.isEmpty()) {
            return emptyList<State>() to emptyList()
        }
        val states = mutableListOf<State>()
        val transitions = mutableListOf<Transition>()

        // Calls drawn from the primary lambda PLUS any folded-in extras:
        //   - extension lambdas (`stateVar { … }` operator-invoke
        //     configuration pattern — see comment near the extension-
        //     collection block below);
        //   - extra calls (receiver-scoped factory calls like
        //     `stateVar.historyState("…")` collected by the caller — they
        //     belong to this state's scope even though they were written
        //     in an outer lambda).
        val allCalls = buildList {
            lambda?.directCallExpressions()?.let { addAll(it) }
            extraLambdas.forEach { addAll(it.directCallExpressions()) }
            addAll(extraCalls)
        }

        // Pre-pass 1: identify every state declaration in this scope that's
        // bound to a Kotlin variable (`val state1 = initialState("…")`). We
        // need this map to recognise later `state1 { … }` calls as extensions
        // of an already-known state rather than mysterious unknown invokes.
        val stateBindings = mutableMapOf<String, KtCallExpression>()
        allCalls.forEach { call ->
            val kind = KStateMachineCalls.matchKind(call)
            if (kind == KStateMachineCalls.Kind.STATE
                || kind == KStateMachineCalls.Kind.ADD_STATE
                || kind == KStateMachineCalls.Kind.MACHINE
            ) {
                call.bindingNameFromAssignment()?.let { stateBindings[it] = call }
            }
        }

        // Pre-pass 2: collect `stateVar { … }` extension invocations whose
        // callee matches a binding we just found. KStateMachine exposes a
        // `State.invoke(builder)` operator for configuring a state after its
        // declaration, used to split `val s = initialState("S")` from
        // `s { transition<E>(…) }`. Their lambda bodies must be folded into
        // the matching state's primary body so the transitions land on the
        // right state instead of being attributed to the surrounding scope.
        val extensionsByBinding = mutableMapOf<String, MutableList<KtLambdaExpression>>()
        allCalls.forEach { call ->
            val callee = call.calleeExpression?.text ?: return@forEach
            if (KStateMachineCalls.matchKind(call) == null && callee in stateBindings) {
                call.dslLambda()?.let {
                    extensionsByBinding.getOrPut(callee) { mutableListOf() } += it
                }
            }
        }

        // Pre-pass 3: collect dot-qualified receiver-scoped factory calls
        // (`stateVar.state("…")`, `stateVar.historyState("…")`,
        // `stateVar.transition<E>(…)`) whose receiver matches a known binding.
        // Each such call belongs to the receiver's state scope rather than
        // the lexical scope it was written in, mirroring the way the
        // KStateMachine DSL resolves the receiver at runtime.
        val receiverScopedByBinding = mutableMapOf<String, MutableList<KtCallExpression>>()
        allCalls.forEach { call ->
            val receiver = call.dottedReceiverName() ?: return@forEach
            if (receiver !in stateBindings) return@forEach
            val kind = KStateMachineCalls.matchKind(call) ?: return@forEach
            if (kind == KStateMachineCalls.Kind.STATE
                || kind == KStateMachineCalls.Kind.ADD_STATE
                || kind == KStateMachineCalls.Kind.MACHINE
                || kind == KStateMachineCalls.Kind.TRANSITION
            ) {
                receiverScopedByBinding.getOrPut(receiver) { mutableListOf() } += call
            }
        }

        // Main pass: produce model objects, skipping extension invocations
        // and receiver-scoped factory calls (both have been folded into the
        // corresponding declaration's extras).
        allCalls.forEach { call ->
            val callee = call.calleeExpression?.text
            if (callee != null
                && KStateMachineCalls.matchKind(call) == null
                && callee in stateBindings
            ) {
                return@forEach
            }
            val dotReceiver = call.dottedReceiverName()
            if (dotReceiver != null && dotReceiver in stateBindings) {
                return@forEach
            }
            when (KStateMachineCalls.matchKind(call)) {
                KStateMachineCalls.Kind.STATE -> {
                    val binding = call.bindingNameFromAssignment()
                    val extras = binding?.let { extensionsByBinding[it] }.orEmpty()
                    val nestedCalls = binding?.let { receiverScopedByBinding[it] }.orEmpty()
                    states += parseState(call, pointerManager, extras, nestedCalls)
                }
                KStateMachineCalls.Kind.ADD_STATE -> {
                    val binding = call.bindingNameFromAssignment()
                    val extras = binding?.let { extensionsByBinding[it] }.orEmpty()
                    val nestedCalls = binding?.let { receiverScopedByBinding[it] }.orEmpty()
                    states += parseAddState(call, pointerManager, extras, nestedCalls)
                }
                KStateMachineCalls.Kind.TRANSITION -> {
                    val transitionName = findArgumentValueWithDefaults(call, NAME_ARGUMENT) ?: "<unnamed>"
                    val calleeText = call.calleeExpression?.text
                    val guardEntry = call.findLambdaAssignmentEntry(GUARD_PROPERTY)
                    val guardRhs = guardEntry?.right
                    transitions += Transition(
                        name = transitionName,
                        pointer = pointerManager.createSmartPsiElementPointer(call),
                        targetGroups = call.findTargetGroups(),
                        eventType = call.typeArguments.firstOrNull()?.text,
                        callee = calleeText,
                        isGuarded = guardEntry != null,
                        guard = if (guardEntry != null && guardRhs != null) {
                            // Point at the whole `guard = …` binary expression
                            // so reverse navigation triggers from a caret on
                            // either the `guard` identifier, the `=`, or the
                            // lambda body. Display text stays the RHS only.
                            Guard(
                                text = guardRhs.text,
                                pointer = pointerManager.createSmartPsiElementPointer(guardEntry),
                            )
                        } else null,
                        // dataTransition<E, D> / dataTransitionOn<E, D> — D is the 2nd type arg.
                        // joinDataTransition<D> — D is the 1st (no event type arg).
                        dataType = when {
                            calleeText in DATA_TRANSITION_CALLEES -> call.typeArguments.getOrNull(1)?.text
                            calleeText == "joinDataTransition" -> call.typeArguments.firstOrNull()?.text
                            else -> null
                        },
                        joinSources = if (calleeText in JOIN_TRANSITION_CALLEES)
                            call.findJoinSources() else emptyList(),
                    )
                }
                KStateMachineCalls.Kind.MACHINE -> {
                    // Nested machine — render it as a substate. StateMachine
                    // extends State so it slots into the children list directly,
                    // and the diagram generator wraps it in its own named block.
                    val binding = call.bindingNameFromAssignment()
                    val extras = binding?.let { extensionsByBinding[it] }.orEmpty()
                    val nestedCalls = binding?.let { receiverScopedByBinding[it] }.orEmpty()
                    states += parseMachine(call, pointerManager, extras, nestedCalls)
                }
                null -> {
                    // Listener bodies (`onEntry`, `onTransitionComplete`, etc.)
                    // are runtime callbacks. They aren't DSL builders, and
                    // descending into them produces false positives — e.g. an
                    // MVI `state { copy(…) }` setter inside an `onTransitionComplete`
                    // body would match the catalog's "state" name and spawn a
                    // phantom node. Skip them.
                    val unknownCallee = call.calleeExpression?.text
                    if (unknownCallee != null && unknownCallee in KStateMachineCalls.LISTENER_FUNCTIONS) {
                        return@forEach
                    }
                    // Unknown callee with a lambda that we couldn't map to a
                    // known state binding above — recurse into its lambda and
                    // attribute any found content to the current scope. Same
                    // fallback as before for foreign helper extensions that
                    // happen to call DSL builders inside.
                    val (nestedStates, nestedTransitions) =
                        parseLambdaChildren(call.dslLambda(), pointerManager)
                    states += nestedStates
                    transitions += nestedTransitions
                }
            }
        }
        return states to transitions
    }

    private fun parseState(
        call: KtCallExpression,
        pointerManager: SmartPointerManager,
        extensionLambdas: List<KtLambdaExpression> = emptyList(),
        extraCalls: List<KtCallExpression> = emptyList(),
    ): State {
        val name = findArgumentValueWithDefaults(call, NAME_ARGUMENT) ?: "<unnamed>"
        val (substates, transitions) = parseLambdaChildren(
            call.dslLambda(), pointerManager, extensionLambdas, extraCalls,
        )
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
            defaultData = if (kind.isData()) call.findDefaultData() else null,
            // choiceState / initialChoiceState / choiceDataState /
            // initialChoiceDataState — the lambda body returns the target state.
            // We collect every statically-resolvable identifier in every return
            // position (if/when branches included), so a `choiceState { if (c) A else B }`
            // surfaces both A and B.
            redirectTargets = if (kind.isChoice()) call.findChoiceRedirectTargets() else emptyList(),
            bindingName = call.bindingNameFromAssignment(),
        )
    }

    // addState / addInitialState / addFinalState take the state instance as the
    // first positional argument and accept a configuration lambda that holds
    // nested transitions and substates. We must recurse into the lambda — the
    // previous implementation skipped it and silently dropped every transition
    // declared this way.
    private fun parseAddState(
        call: KtCallExpression,
        pointerManager: SmartPointerManager,
        extensionLambdas: List<KtLambdaExpression> = emptyList(),
        extraCalls: List<KtCallExpression> = emptyList(),
    ): State {
        val name = findArgumentValueWithDefaults(call, STATE_ARGUMENT) ?: "<unknown>"
        val (substates, transitions) = parseLambdaChildren(
            call.dslLambda(), pointerManager, extensionLambdas, extraCalls,
        )
        return State(
            name = name,
            states = substates,
            transitions = transitions,
            pointer = pointerManager.createSmartPsiElementPointer(call),
            kind = call.addStateKindFromCallee(),
            isParallel = call.isParallelChildMode(),
            bindingName = call.bindingNameFromAssignment(),
        )
    }

    // createStateMachine / createStateMachineBlocking / createStdLibStateMachine —
    // builds a StateMachine (a State subclass) from the call's lambda. Used for
    // top-level machines AND nested machines (substates of another machine).
    private fun parseMachine(
        call: KtCallExpression,
        pointerManager: SmartPointerManager,
        extensionLambdas: List<KtLambdaExpression> = emptyList(),
        extraCalls: List<KtCallExpression> = emptyList(),
    ): StateMachine {
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
        val (states, transitions) = parseLambdaChildren(
            call.dslLambda(), pointerManager, extensionLambdas, extraCalls,
        )
        return StateMachine(
            name = name,
            states = states,
            transitions = transitions,
            pointer = pointerManager.createSmartPsiElementPointer(call),
            isParallel = call.isParallelChildMode(),
            bindingName = call.bindingNameFromAssignment(),
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

/**
 * For `joinTransition(s1, s2, …, name = …, targetState = …)`, collect the
 * positional (unnamed) arguments — those are the vararg join-point state
 * references. Named args (`name`, `targetState`) are excluded.
 */
private fun KtCallExpression.findJoinSources(): List<String> =
    valueArgumentList?.arguments.orEmpty()
        .filter { it.getArgumentName() == null }
        .mapNotNull { arg ->
            val expr = arg.getArgumentExpression() ?: return@mapNotNull null
            resolveStateNameFromExpr(expr) ?: expr.targetFallbackText() ?: expr.text
        }

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

private fun StateKind.isChoice(): Boolean = when (this) {
    StateKind.CHOICE,
    StateKind.INITIAL_CHOICE,
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

/**
 * Collect every statically-resolvable target this transition can route to. A
 * single-target transition (`transition<E>(targetState = X)`) returns one
 * one-target group; a branching `transitionOn { targetState = { if … else … } }`
 * returns one group per branch; a `transitionConditionally { direction = { … } }`
 * returns one group per `targetState(…)` / `targetParallelStates(…)` call found
 * inside the direction lambda, with `isParallel = true` for the latter so the
 * diagram can render it as a fork.
 *
 * Each target name is resolved structurally via [resolveStateNameFromExpr],
 * falling back to the raw expression text — same rule the rest of the pipeline
 * already uses, keeping cross-file object references informative even without
 * a binding context.
 */
private fun KtCallExpression.findTargetGroups(): List<TargetGroup> {
    // (1) Named value argument: `transition<E>(targetState = Foo)`
    val valueArgExpr = valueArgumentList?.arguments
        ?.firstOrNull { it.getArgumentName()?.asName?.asString() == TARGET_STATE_PROPERTY }
        ?.getArgumentExpression()
    if (valueArgExpr != null) {
        val name = resolveStateNameFromExpr(valueArgExpr) ?: valueArgExpr.text
        return listOf(TargetGroup(targets = listOf(name), isParallel = false))
    }

    // (2) transitionConditionally — look for `direction = { … }` lambda and walk
    // it for targetState / targetParallelStates calls.
    val callee = calleeExpression?.text
    if (callee == TRANSITION_CONDITIONALLY_CALLEE) {
        val directionLambda =
            findLambdaAssignmentEntry(DIRECTION_PROPERTY)?.right as? KtLambdaExpression
                ?: return emptyList()
        return extractConditionalGroups(directionLambda)
    }

    // (3) Other forms — `targetState = …` inside the transition lambda. The
    // RHS is either an expression (transition / dataTransition) or another
    // lambda (transitionOn / dataTransitionOn).
    val targetEntry = findLambdaAssignmentEntry(TARGET_STATE_PROPERTY) ?: return emptyList()
    val rhs = targetEntry.right ?: return emptyList()
    if (rhs is KtLambdaExpression) {
        // transitionOn-style — each branch in the lambda is one alternative
        // single-target outcome.
        return extractDirectTargets(rhs).map { TargetGroup(targets = listOf(it), isParallel = false) }
    }
    val name = resolveStateNameFromExpr(rhs) ?: rhs.text
    return listOf(TargetGroup(targets = listOf(name), isParallel = false))
}

/**
 * Walk a `transitionConditionally { direction = { … } }` body looking for
 * KStateMachine direction-builder calls (`targetState(s)` / `targetParallelStates(s1, s2…)`).
 * Each call becomes one [TargetGroup]; `targetParallelStates` produces a group
 * with `isParallel = true` so the renderer emits a `<<fork>>` pseudo-state.
 * Other call shapes (`noTransition()`, `stay()`, user helpers) are ignored.
 */
private fun extractConditionalGroups(directionLambda: KtLambdaExpression): List<TargetGroup> {
    val body = directionLambda.bodyExpression ?: return emptyList()
    val groups = mutableListOf<TargetGroup>()
    fun walk(element: PsiElement) {
        for (child in element.children) {
            if (child is KtLambdaExpression) continue
            if (child is KtCallExpression) {
                when (child.calleeExpression?.text) {
                    TARGET_STATE_CALL, TARGET_PARALLEL_STATES_CALL -> {
                        val callee = child.calleeExpression?.text
                        val args = child.valueArguments
                            .mapNotNull { it.getArgumentExpression() }
                            .mapNotNull { resolveStateNameFromExpr(it) ?: it.targetFallbackText() }
                            .distinct()
                        if (args.isNotEmpty()) {
                            groups += TargetGroup(
                                targets = args,
                                isParallel = callee == TARGET_PARALLEL_STATES_CALL,
                            )
                        }
                    }
                    STAY_CALL -> {
                        // `stay()` fires the transition but keeps the source state
                        // active — render as a self-loop. Modelled as an empty,
                        // non-parallel group flagged with isSelfLoop so the
                        // generator can resolve the target to the source at
                        // render time (we don't know the source's id here).
                        groups += TargetGroup(targets = emptyList(), isParallel = false, isSelfLoop = true)
                    }
                    NO_TRANSITION_CALL -> {
                        // Explicit no-op outcome — contributes no group on
                        // purpose. The diagram skips emission when all groups
                        // are absent / empty, matching the runtime semantics.
                    }
                    // Other call shapes (user helpers, conditional builders
                    // we don't recognize) intentionally fall through and are
                    // ignored; the parent walk will descend into their args.
                }
            }
            walk(child)
        }
    }
    walk(body)
    return groups
}

/**
 * Walk return-position expressions in [lambda] and collect every
 * statically-resolvable state reference. Used for `transitionOn`-style
 * `targetState = { … }` lambdas (each return point is one alternative branch)
 * and for `choiceState`-family bodies (same shape, the lambda returns a
 * single `State`). De-duplicates so `{ if (c) A else A }` yields one entry.
 */
private fun extractDirectTargets(lambda: KtLambdaExpression): List<String> {
    val result = LinkedHashSet<String>()
    fun consider(expr: KtExpression?) {
        if (expr == null) return
        when (expr) {
            is KtBlockExpression -> consider(expr.statements.lastOrNull())
            is KtIfExpression -> {
                consider(expr.then)
                consider(expr.`else`)
            }
            is KtWhenExpression -> expr.entries.forEach { consider(it.expression) }
            else -> {
                val name = resolveStateNameFromExpr(expr) ?: expr.targetFallbackText()
                if (name != null) result += name
            }
        }
    }
    lambda.bodyExpression?.statements?.lastOrNull()?.let(::consider)
    return result.toList()
}

/**
 * Last-resort fallback for an expression in target position: a bare identifier
 * (or the selector of a dotted reference) maps to its text; everything else
 * (operators, literals, opaque calls) returns null so we don't pollute the
 * target list with noise from condition expressions inside the same lambda.
 */
private fun KtExpression.targetFallbackText(): String? = when (this) {
    is KtNameReferenceExpression -> text
    is KtDotQualifiedExpression -> (selectorExpression as? KtNameReferenceExpression)?.text
    else -> null
}

/**
 * Follow [expr] back to the underlying KStateMachine state-factory call (if any)
 * and return that call's name argument. Handles:
 *
 *   - String literal: returns the literal text directly.
 *   - Direct factory call (`state("X")` / `addState(MyState())` / `createStateMachine(…, "Y")`):
 *     returns the name extracted from the call's arguments.
 *   - Identifier (`KtNameReferenceExpression`): tries, in order —
 *       1. local/file val/var: walk back to its declaration and recurse on
 *          the initializer (so chains like `val a = state("X"); val b = a`
 *          still resolve to `"X"`);
 *       2. same-file object declaration: `object State2 : DefaultState()`
 *          (top-level OR nested inside a sealed-class body — the standard
 *          KStateMachine state-singleton pattern) → returns the object's own
 *          name.
 *
 * Capped recursion depth as a safety belt; null when nothing resolves.
 */
private fun resolveStateNameFromExpr(expr: KtExpression?, depth: Int = 0): String? {
    if (expr == null || depth > 5) return null
    return when (expr) {
        is KtStringTemplateExpression -> expr.text
        is KtCallExpression -> when (KStateMachineCalls.matchKind(expr)) {
            KStateMachineCalls.Kind.STATE, KStateMachineCalls.Kind.MACHINE ->
                findArgumentValueWithDefaults(expr, NAME_ARGUMENT)
            KStateMachineCalls.Kind.ADD_STATE ->
                findArgumentValueWithDefaults(expr, STATE_ARGUMENT)
            else -> null
        }
        is KtNameReferenceExpression -> {
            // Try the val/var-binding path first; if nothing there, try the
            // same-file object-declaration path (covers the
            // `sealed class HeroState { object Standing : HeroState() }`
            // pattern that KStateMachine users overwhelmingly favour).
            expr.resolveLocalInitializer()
                ?.let { return resolveStateNameFromExpr(it, depth + 1) }
            expr.findObjectDeclarationInFile()
        }
        // Anonymous-object initializers like `object : DefaultState("first") {}`
        // — pull the name from the super-type constructor's string argument so
        // the resolver doesn't fall back to the raw object-literal source.
        is KtObjectLiteralExpression -> expr.extractObjectLiteralName()
        // `this@stateLabel` inside an extension lambda (`state1 { … this@state1 … }`)
        // refers to the state held in the labelled variable. Return the label
        // so the downstream resolver matches it against the state's bindingName.
        is KtThisExpression -> expr.getLabelName()
        // Receiver-scoped state-factory calls (`state2.historyState("…")`,
        // `state2.state("…")`, …) — the actual factory call is the selector;
        // recurse into it so the chain resolves to the new state's name.
        is KtDotQualifiedExpression ->
            resolveStateNameFromExpr(expr.selectorExpression, depth + 1)
        else -> null
    }
}

/**
 * Look for an `object <this.text>` declaration anywhere in the same file:
 *   - top-level objects, and
 *   - objects nested inside top-level `class`/`sealed class` bodies (typical
 *     `sealed class HeroState { object Standing : HeroState() }` shape).
 * Returns the object's own name when found.
 */
private fun KtNameReferenceExpression.findObjectDeclarationInFile(): String? {
    val target = text
    fun search(declarations: List<org.jetbrains.kotlin.psi.KtDeclaration>): String? {
        for (decl in declarations) {
            when (decl) {
                is KtObjectDeclaration -> if (decl.name == target) return decl.name
                is KtClass -> decl.body?.declarations?.let { nested ->
                    search(nested)?.let { return it }
                }
            }
        }
        return null
    }
    return search(containingKtFile.declarations)
}

/**
 * Walk the call's trailing-lambda body looking for `propertyName = <expr>`
 * (a simple-name assignment) and return the whole binary expression. Callers
 * needing only the right-hand text use [findLambdaAssignment]; callers
 * needing to navigate to the assignment use the returned element directly
 * (its range covers both the property name and the right-hand expression,
 * so a caret on either side resolves to the same node). Skips nested lambdas
 * so we stay in the immediate transition scope.
 */
private fun KtCallExpression.findLambdaAssignmentEntry(propertyName: String): KtBinaryExpression? {
    val body = dslLambda()?.bodyExpression ?: return null
    var found: KtBinaryExpression? = null
    fun walk(element: PsiElement) {
        if (found != null) return
        for (child in element.children) {
            if (child is KtLambdaExpression) continue
            if (child is KtBinaryExpression
                && child.operationToken == KtTokens.EQ
                && child.left?.text == propertyName
            ) {
                found = child
                return
            }
            walk(child)
        }
    }
    walk(body)
    return found
}

private fun KtCallExpression.findLambdaAssignment(propertyName: String): String? =
    findLambdaAssignmentEntry(propertyName)?.right?.text

/**
 * For `choiceState`-family calls, walk the trailing lambda body and surface every
 * statically-resolvable target the body can return. Single-expression bodies
 * yield one entry; branching bodies (`{ if (cond) A else B }`, `when { … }`)
 * yield one entry per resolved branch. Returns empty when nothing structurally
 * resolves — same fallback policy as [extractDirectTargets].
 */
private fun KtCallExpression.findChoiceRedirectTargets(): List<String> {
    val lambda = dslLambda() ?: return emptyList()
    return extractDirectTargets(lambda)
}

/**
 * Extract the `defaultData` argument value from a data-state factory call.
 * Accepts either form:
 *   dataState<D>("name", defaultData = MyData(5))           // named
 *   dataState<D>("name", MyData(5))                          // positional (slot 1)
 *
 * Positional slot 1 is correct for every data-state factory signature in the
 * KStateMachine catalog — `name` is slot 0, `defaultData` is slot 1. The
 * caller only invokes this when [StateKind.isData] is true, so we don't risk
 * misreading a non-data-state call's slot-1 argument.
 */
private fun KtCallExpression.findDefaultData(): String? {
    val args = valueArgumentList?.arguments.orEmpty()
    args.firstOrNull { it.getArgumentName()?.asName?.asString() == DEFAULT_DATA_ARGUMENT }
        ?.getArgumentExpression()?.text
        ?.let { return it }
    // Positional fallback: slot 1.
    val positional = args.filter { it.getArgumentName() == null }
    return positional.getOrNull(1)?.getArgumentExpression()?.text
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

/**
 * If this call sits as the right-hand side of `val x = factory(…)` or
 * `x = factory(…)`, returns the bound variable name (`"x"`). Also looks
 * through a `KtDotQualifiedExpression` wrapper so the dotted receiver form
 * `val x = receiver.factory(…)` is recognised — without that lookup the
 * binding name wouldn't be captured for receiver-scoped state declarations
 * like `state2.historyState("…")`.
 */
/**
 * If this call is the selector of a `receiver.factory(…)` dot-qualified
 * expression AND the receiver is a plain name reference, returns the receiver
 * name. Used by the parser to recognise receiver-scoped DSL invocations like
 * `state2.historyState("…")` so the result is attributed to `state2`'s scope
 * rather than the lexical scope the call was written in.
 */
private fun KtCallExpression.dottedReceiverName(): String? {
    val dot = parent as? KtDotQualifiedExpression ?: return null
    if (dot.selectorExpression !== this) return null
    return (dot.receiverExpression as? KtNameReferenceExpression)?.text
}

private fun KtCallExpression.bindingNameFromAssignment(): String? {
    val containing: PsiElement = (parent as? KtDotQualifiedExpression) ?: this
    val p = containing.parent ?: return null
    return when {
        p is KtBinaryExpression && p.operationToken == KtTokens.EQ && p.right === containing ->
            (p.left as? KtNameReferenceExpression)?.text
        p is KtProperty && p.initializer === containing -> p.name
        else -> null
    }
}

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
//
// Name references like `addInitialState(state)` where `state` is a local
// val/var bound to a constructor call are recursively resolved — the val's
// initializer is fed back through simplifiedStateName, so
//   val state = DefaultState()
//   addInitialState(state)   →   "DefaultState"
//
// Anonymous object literals (`val s = object : DefaultState("first") {}` then
// `addInitialState(s)`) resolve to the string-literal name passed to the
// superclass constructor when present, falling back to the superclass type
// name. Without this branch the resolver returned the entire object-literal
// source text, which PlantUML can't read as a state name.
//
// Capped at a small recursion depth to defend against pathological val chains.
private fun KtExpression.simplifiedStateName(depth: Int = 0): String {
    if (depth > 5) return text
    return when (this) {
        is KtCallExpression -> calleeExpression?.text ?: text
        is KtNameReferenceExpression ->
            resolveLocalInitializer()?.simplifiedStateName(depth + 1) ?: text
        is KtObjectLiteralExpression -> extractObjectLiteralName() ?: text
        else -> text
    }
}

/**
 * `object : SomeState("name") {}` → returns `"\"name\""` (quoted, matching
 * the string-literal convention used elsewhere for state names).
 * `object : SomeState() {}` → returns `"SomeState"` (the supertype name).
 * Anything more exotic (multi-arg constructor, no super-type call) → null,
 * so callers fall back to whatever else they would have done.
 */
private fun KtObjectLiteralExpression.extractObjectLiteralName(): String? {
    val callEntry = objectDeclaration.superTypeListEntries
        .filterIsInstance<KtSuperTypeCallEntry>()
        .firstOrNull()
        ?: return null
    val firstArg = callEntry.valueArguments.firstOrNull()?.getArgumentExpression()
    if (firstArg is KtStringTemplateExpression) return firstArg.text
    return callEntry.typeReference?.text
}

/**
 * Walk up the PSI looking for the binding of [this] reference. Returns the
 * expression that the name currently refers to, or null if nothing resolves.
 *
 * Looks at, in lexical-scope order:
 *   - `KtBlockExpression` statements: `val/var <name> = expr` declarations,
 *     **and** plain assignments `<name> = expr` (covers the common KSM test
 *     pattern of `private var state1: State? = null` at class level then
 *     `state1 = initialState("…")` inside a `createStateMachine { … }` lambda,
 *     where the declaration's null initializer isn't useful).
 *   - `KtClassBody` declarations: member properties of the enclosing class.
 *   - Top-level file properties.
 *
 * The *latest* matching binding within each scope wins, so a re-assignment
 * after an earlier declaration is reflected.
 *
 * Differs from [findLocalStringConstant] — that one only returns string
 * literals; this one returns ANY initializer expression so the caller (e.g.
 * [simplifiedStateName], [resolveStateNameFromExpr]) can decide what to do
 * with it.
 */
private fun KtNameReferenceExpression.resolveLocalInitializer(): KtExpression? {
    val targetName = text
    var element: PsiElement? = parent
    while (element != null) {
        if (element is KtBlockExpression) {
            var lastMatch: KtExpression? = null
            for (stmt in element.statements) {
                when {
                    stmt is KtProperty && stmt.name == targetName ->
                        stmt.initializer?.let { lastMatch = it }
                    stmt is KtBinaryExpression
                        && stmt.operationToken == KtTokens.EQ
                        && (stmt.left as? KtNameReferenceExpression)?.text == targetName ->
                        stmt.right?.let { lastMatch = it }
                }
            }
            if (lastMatch != null) return lastMatch
        } else if (element is KtClassBody) {
            element.declarations
                .filterIsInstance<KtProperty>()
                .firstOrNull { it.name == targetName }
                ?.initializer
                ?.let { return it }
        }
        element = element.parent
    }
    // Fall back to top-level file properties.
    return containingKtFile.declarations.filterIsInstance<KtProperty>()
        .firstOrNull { it.name == targetName }
        ?.initializer
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
