package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow.actions

import com.github.nsk90.kstatemachineintellijplatformplugin.psi.DiagramSyntax
import com.github.nsk90.kstatemachineintellijplatformplugin.services.StateMachineViewService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

private const val PLAYGROUND_TAB_INDEX = 2

/**
 * Returns the diagram source and syntax for whichever tab is currently visible:
 * - Playground tab → source text from the playground editor
 * - Diagram tab (or Structure tab) → source from the Diagram panel
 *
 * Returns null when the active tab has no content yet.
 */
internal fun activeSourceAndSyntax(project: Project): Pair<String, DiagramSyntax>? {
    val service = project.service<StateMachineViewService>()
    return if (service.activeTabIndex == PLAYGROUND_TAB_INDEX) {
        val pg = service.playgroundPanel ?: return null
        val src = pg.currentSource ?: return null
        src to pg.syntax
    } else {
        val dp = service.diagramPanel ?: return null
        val src = dp.currentPlantUml ?: return null
        src to dp.currentSyntax
    }
}

/**
 * Returns the rendered SVG for the currently active tab, or null if no render
 * has completed yet.
 */
internal fun activeSvg(project: Project): String? {
    val service = project.service<StateMachineViewService>()
    return if (service.activeTabIndex == PLAYGROUND_TAB_INDEX) {
        service.playgroundPanel?.currentSvg
    } else {
        val dp = service.diagramPanel ?: return null
        when (dp.currentSyntax) {
            DiagramSyntax.PLANTUML -> dp.currentPlantUmlSvg
            DiagramSyntax.MERMAID -> dp.currentMermaidSvg
        }
    }
}
