package com.github.nsk90.kstatemachineintellijplatformplugin.model

import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.psi.KtCallExpression

enum class StateKind {
    STATE,
    INITIAL,
    FINAL,
    INITIAL_FINAL,
    DATA,
    INITIAL_DATA,
    FINAL_DATA,
    INITIAL_FINAL_DATA,
    MUTABLE_DATA,
    INITIAL_MUTABLE_DATA,
    FINAL_MUTABLE_DATA,
    INITIAL_FINAL_MUTABLE_DATA,
    CHOICE,
    INITIAL_CHOICE,
    CHOICE_DATA,
    INITIAL_CHOICE_DATA,
    HISTORY,
    HISTORY_DEEP,
}

open class State(
    val name: String,
    val states: List<State>,
    val transitions: List<Transition>,
    val pointer: SmartPsiElementPointer<KtCallExpression>? = null,
    val kind: StateKind = StateKind.STATE,
    val isParallel: Boolean = false,
    /** For `dataState<D>` and its initial/final/choice variants — the `D` type-arg text, e.g. `MyPayload`. */
    val dataType: String? = null,
    /** For data states — raw expression text passed to the `defaultData` argument (`null` when absent). */
    val defaultData: String? = null,
    /**
     * For `choiceState`-family pseudo-states — every resolvable target the lambda
     * body can return. Multiple entries when the body branches
     * (`{ if (cond) A else B }`); empty when the body has no statically-extractable
     * state reference. `choiceState`'s lambda returns a single `State`, so unlike
     * transitions there is no parallel-split notion here — every entry is one
     * alternative outcome.
     */
    val redirectTargets: List<String> = emptyList(),
    /**
     * Name of the Kotlin variable this state was assigned to, when the state
     * factory call is the RHS of `val x = state(…)` / `x = state(…)`. Lets
     * transition targets that reference the variable (`targetParallelStates(x)`)
     * resolve to this state even when the state's own [name] is unnamed.
     */
    val bindingName: String? = null,
) {
    /** First redirect target — convenience for callers that only need a single value. */
    val redirectTarget: String? get() = redirectTargets.firstOrNull()
}
