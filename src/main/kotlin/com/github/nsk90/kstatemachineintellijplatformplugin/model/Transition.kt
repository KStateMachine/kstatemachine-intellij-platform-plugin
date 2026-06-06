package com.github.nsk90.kstatemachineintellijplatformplugin.model

import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.psi.KtCallExpression

class Transition(
    val name: String,
    val pointer: SmartPsiElementPointer<KtCallExpression>? = null,
    /**
     * Every statically-resolvable outcome of the transition. Empty when the
     * transition has no target (true internal/self) OR when the target lives
     * in a lambda the parser couldn't statically analyse.
     */
    val targetGroups: List<TargetGroup> = emptyList(),
    val eventType: String? = null,
    /** Raw DSL function name: `transition`, `transitionOn`, `transitionConditionally`, `dataTransition`, `dataTransitionOn`. */
    val callee: String? = null,
    /** True if the transition lambda assigns `guard = …`. */
    val isGuarded: Boolean = false,
    /** Captured `guard = …` expression — `null` when the transition is not guarded or the guard couldn't be parsed. */
    val guard: Guard? = null,
    /** For `dataTransition<E, D>` / `dataTransitionOn<E, D>` — the `D` type-arg text. */
    val dataType: String? = null,
    /** For `joinTransition(s1, s2, …)` — the join-point state names from the vararg positions. */
    val joinSources: List<String> = emptyList(),
) {
    /** First target across all groups — convenience for callers that only need a single target. */
    val targetStateName: String? get() = targetGroups.firstOrNull()?.targets?.firstOrNull()

    /** All target names flattened across groups. Used by the tree's right-click navigation. */
    val allTargetNames: List<String> get() = targetGroups.flatMap { it.targets }
}
