package com.github.nsk90.kstatemachineintellijplatformplugin.model

import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.psi.KtCallExpression

class StateMachine(
    name: String,
    states: List<State>,
    transitions: List<Transition>,
    pointer: SmartPsiElementPointer<KtCallExpression>? = null,
    isParallel: Boolean = false,
    bindingName: String? = null,
    umlMetaInfo: UmlMetaInfo? = null,
) : State(
    name = name,
    states = states,
    transitions = transitions,
    pointer = pointer,
    kind = StateKind.STATE,
    isParallel = isParallel,
    bindingName = bindingName,
    umlMetaInfo = umlMetaInfo,
)
