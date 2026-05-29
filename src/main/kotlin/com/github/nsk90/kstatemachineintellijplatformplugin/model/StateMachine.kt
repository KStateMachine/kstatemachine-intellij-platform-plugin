package com.github.nsk90.kstatemachineintellijplatformplugin.model

import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.psi.KtCallExpression

class StateMachine(
    name: String,
    states: List<State>,
    transitions: List<Transition>,
    pointer: SmartPsiElementPointer<KtCallExpression>? = null,
) : State(name, states, transitions, pointer, StateKind.STATE)
