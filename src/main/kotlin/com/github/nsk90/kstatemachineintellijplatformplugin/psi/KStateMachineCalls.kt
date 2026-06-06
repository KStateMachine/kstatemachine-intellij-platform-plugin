package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.intellij.icons.AllIcons
import org.jetbrains.kotlin.psi.KtCallExpression
import javax.swing.Icon

/**
 * Shared catalog of KStateMachine DSL function FQNs.
 *
 * Both [PsiElementsParser] (to build the model) and [StateMachineLineMarkerProvider]
 * (to gate gutter icons) classify calls through this object so they stay in sync.
 */
internal object KStateMachineCalls {

    enum class Kind(val icon: Icon, val tooltip: String) {
        MACHINE(AllIcons.Nodes.Module, "KStateMachine: createStateMachine"),
        STATE(AllIcons.Nodes.Class, "KStateMachine: state"),
        ADD_STATE(AllIcons.Nodes.Class, "KStateMachine: addState"),
        TRANSITION(AllIcons.Actions.Forward, "KStateMachine: transition"),
    }

    internal data class Declaration(val name: String, val import: String, val kind: Kind) {
        val fullName get() = "$import.$name"
    }

    private val DECLARATIONS: List<Declaration> = buildList {
        // Machine factories
        add(Declaration("createStateMachine", "ru.nsk.kstatemachine.coroutines", Kind.MACHINE))
        add(Declaration("createStateMachineBlocking", "ru.nsk.kstatemachine.statemachine", Kind.MACHINE))
        add(Declaration("createStdLibStateMachine", "ru.nsk.kstatemachine.statemachine", Kind.MACHINE))
        // Internal test-only factory used throughout KStateMachine's own unit
        // tests. Not part of the public API — included here because the plugin
        // owner needs the tool window to work in the library's test sources.
        add(Declaration("createTestStateMachine", "ru.nsk.kstatemachine.statemachine", Kind.MACHINE))
        // State factories
        listOf(
            "state", "dataState",
            "initialState", "initialDataState",
            "finalState", "finalDataState",
            "initialFinalState", "initialFinalDataState",
            "choiceState", "initialChoiceState",
            "choiceDataState", "initialChoiceDataState",
            "historyState",
            // Mutable-data variants (same shape as dataState, but produce
            // MutableDataState<D> / FinalMutableDataState<D>).
            "mutableDataState", "initialMutableDataState",
            "finalMutableDataState", "initialFinalMutableDataState",
        ).forEach { add(Declaration(it, "ru.nsk.kstatemachine.state", Kind.STATE)) }
        // Add-state forms
        add(Declaration("addState", "ru.nsk.kstatemachine.state.State", Kind.ADD_STATE))
        add(Declaration("addState", "ru.nsk.kstatemachine.state", Kind.ADD_STATE))
        add(Declaration("addInitialState", "ru.nsk.kstatemachine.state", Kind.ADD_STATE))
        add(Declaration("addFinalState", "ru.nsk.kstatemachine.state", Kind.ADD_STATE))
        // Transitions
        listOf(
            "transition", "transitionOn", "transitionConditionally",
            "dataTransition", "dataTransitionOn",
            "joinTransition", "joinDataTransition",
        ).forEach { add(Declaration(it, "ru.nsk.kstatemachine.state", Kind.TRANSITION)) }
    }

    private val NAMES: Set<String> = DECLARATIONS.mapTo(mutableSetOf()) { it.name }

    /**
     * Functions that take a lambda *runtime* callback — listeners, lifecycle
     * hooks, transition triggers. Their lambda bodies are user code, not DSL
     * declarations, so the parser must NOT descend into them when scanning a
     * state-builder scope. Otherwise calls like `state { copy(…) }` (MVI setter,
     * Compose state, etc.) inside listener bodies would false-positive against
     * the KStateMachine `state(…)` factory and spawn phantom nodes.
     */
    val LISTENER_FUNCTIONS: Set<String> = setOf(
        "onEntry", "onExit", "onFinished", "onTriggered",
        "onStateEntry", "onStateExit", "onStateFinished",
        "onTransitionTriggered", "onTransitionComplete",
        "onStarted", "onStopped", "onDestroyed",
    )

    /** Cheap name-only check, suitable for the highlighting fast path. */
    fun hasKnownName(name: String): Boolean = name in NAMES

    /**
     * Classify a call against the catalog.
     *
     * Matches purely by callee name. We tried FQN-based gating earlier but it
     * silently dropped calls in real projects (binding-context resolution can
     * be flaky for reified type-arg calls, partial classpaths, and during
     * indexing). The catalog names are specific enough — `createStateMachine`,
     * `initialState`, `transitionConditionally`, etc. — that false positives
     * from unrelated user code are extremely unlikely.
     */
    fun matchKind(call: KtCallExpression): Kind? {
        val text = call.calleeExpression?.text ?: return null
        return kindForName(text)
    }

    private fun kindForName(name: String): Kind? =
        DECLARATIONS.firstOrNull { it.name == name }?.kind

    /** Like [matchKind] but pre-filtered to a specific subset of declarations. */
    fun matchesAny(call: KtCallExpression, predicate: (Kind) -> Boolean): Boolean =
        matchKind(call)?.let(predicate) == true

    internal fun declarationsByKind(kind: Kind): List<Declaration> = DECLARATIONS.filter { it.kind == kind }
}
