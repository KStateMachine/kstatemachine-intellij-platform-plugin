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
    CHOICE,
    INITIAL_CHOICE,
    CHOICE_DATA,
    INITIAL_CHOICE_DATA,
    HISTORY,
}

open class State(
    val name: String,
    val states: List<State>,
    val transitions: List<Transition>,
    val pointer: SmartPsiElementPointer<KtCallExpression>? = null,
    val kind: StateKind = StateKind.STATE,
)
