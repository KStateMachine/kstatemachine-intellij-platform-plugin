package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

/**
 * Copies the diagram source (PlantUML or Mermaid) for whichever tab is active:
 * the Diagram tab's generated source or the Playground tab's edited source.
 * Class name kept as-is for binary-compat with any persisted action-id references.
 */
class CopyPlantUmlAction : AnAction(
    "Copy Diagram Source",
    "Copy the active diagram source (PlantUML or Mermaid) to the clipboard",
    AllIcons.Actions.Copy,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (source, _) = activeSourceAndSyntax(project) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(source))
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { activeSourceAndSyntax(it) } != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
