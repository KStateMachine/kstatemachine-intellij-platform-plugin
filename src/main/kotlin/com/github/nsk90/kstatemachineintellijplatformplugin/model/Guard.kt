package com.github.nsk90.kstatemachineintellijplatformplugin.model

import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.psi.KtExpression

/**
 * Captured right-hand side of a `guard = <expr>` assignment inside a transition
 * lambda. [text] is the raw expression source (typically a lambda body like
 * `{ event.payload == 1 }`); [pointer] points at that expression in the
 * containing Kotlin file so the tree can navigate to it.
 */
class Guard(
    val text: String,
    val pointer: SmartPsiElementPointer<KtExpression>? = null,
)
