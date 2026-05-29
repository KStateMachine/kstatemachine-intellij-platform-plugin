package com.github.nsk90.kstatemachineintellijplatformplugin.psi

import com.github.nsk90.kstatemachineintellijplatformplugin.services.StateMachineViewService
import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.util.Function
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import java.util.function.Supplier

/**
 * Adds an editor gutter icon next to every call to a KStateMachine DSL function
 * (createStateMachine, state, transition, …). Clicking the icon activates the
 * tool window and selects the matching tree node.
 *
 * Anchors on the callee identifier's leaf token — required by the IntelliJ
 * highlighting subsystem so the gutter stays aligned during edits.
 */
class StateMachineLineMarkerProvider : LineMarkerProvider {

    // Fast pass — runs during regular highlighting. We do only cheap text checks here
    // and defer the FQN resolution to collectSlowLineMarkers.
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(
        elements: MutableList<out PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>,
    ) {
        for (element in elements) {
            if (element !is LeafPsiElement) continue
            val callee = element.parent as? KtNameReferenceExpression ?: continue
            val call = callee.parent as? KtCallExpression ?: continue
            if (callee != call.calleeExpression) continue
            if (!KStateMachineCalls.hasKnownName(element.text)) continue
            val kind = KStateMachineCalls.matchKind(call) ?: continue
            result += createMarker(element, call, kind)
        }
    }

    private fun createMarker(
        leaf: LeafPsiElement,
        call: KtCallExpression,
        kind: KStateMachineCalls.Kind,
    ): LineMarkerInfo<PsiElement> {
        val anchor: PsiElement = leaf
        val tooltip: Function<in PsiElement, String> = Function { kind.tooltip }
        val handler: GutterIconNavigationHandler<PsiElement> = GutterIconNavigationHandler { _, _ ->
            call.project.service<StateMachineViewService>().activateAndSelect(call)
        }
        val accessibleName: Supplier<String> = Supplier { "KStateMachine marker" }
        return LineMarkerInfo<PsiElement>(
            anchor,
            anchor.textRange,
            kind.icon,
            tooltip,
            handler,
            GutterIconRenderer.Alignment.LEFT,
            accessibleName,
        )
    }
}
