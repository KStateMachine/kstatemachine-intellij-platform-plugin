package com.github.nsk90.kstatemachineintellijplatformplugin.model

import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.psi.KtCallExpression

class Transition(
    val name: String,
    val pointer: SmartPsiElementPointer<KtCallExpression>? = null,
    val targetStateName: String? = null,
    val eventType: String? = null,
    /** Raw DSL function name: `transition`, `transitionOn`, `transitionConditionally`, `dataTransition`, `dataTransitionOn`. */
    val callee: String? = null,
    /** True if the transition lambda assigns `guard = …`. */
    val isGuarded: Boolean = false,
    /** Captured `guard = …` expression — `null` when the transition is not guarded or the guard couldn't be parsed. */
    val guard: Guard? = null,
    /** For `dataTransition<E, D>` / `dataTransitionOn<E, D>` — the `D` type-arg text. */
    val dataType: String? = null,
)
