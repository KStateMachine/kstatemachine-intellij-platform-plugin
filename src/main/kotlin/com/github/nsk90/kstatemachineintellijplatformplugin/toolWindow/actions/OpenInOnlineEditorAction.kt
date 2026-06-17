package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow.actions

import com.github.nsk90.kstatemachineintellijplatformplugin.psi.DiagramSyntax
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class OpenInOnlineEditorAction : AnAction(
    "Open in Online Editor",
    "Open the current diagram in plantuml.com (PlantUML) or mermaid.live (Mermaid)",
    AllIcons.General.Web,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (source, syntax) = activeSourceAndSyntax(project) ?: return
        val url = when (syntax) {
            DiagramSyntax.PLANTUML ->
                "https://www.plantuml.com/plantuml/uml/${DiagramEncoder.encodePlantUml(source)}"
            DiagramSyntax.MERMAID ->
                "https://mermaid.live/edit#pako:${DiagramEncoder.encodeMermaid(source)}"
        }
        BrowserUtil.browse(url)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { activeSourceAndSyntax(it) } != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
