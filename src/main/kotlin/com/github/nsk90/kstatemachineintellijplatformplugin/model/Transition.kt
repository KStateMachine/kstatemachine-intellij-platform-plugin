package com.github.nsk90.kstatemachineintellijplatformplugin.model

import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.psi.KtCallExpression

class Transition(
    val name: String,
    val pointer: SmartPsiElementPointer<KtCallExpression>? = null,
    val targetStateName: String? = null,
    val eventType: String? = null,
)
