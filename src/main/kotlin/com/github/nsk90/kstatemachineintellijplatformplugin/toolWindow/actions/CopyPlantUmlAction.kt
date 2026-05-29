package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow.actions

import com.github.nsk90.kstatemachineintellijplatformplugin.services.StateMachineViewService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

class CopyPlantUmlAction : AnAction(
    "Copy PlantUML",
    "Copy generated PlantUML source to the clipboard",
    AllIcons.Actions.Copy,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val source = project.service<StateMachineViewService>().diagramPanel?.currentPlantUml ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(source))
    }

    override fun update(e: AnActionEvent) {
        val source = e.project?.service<StateMachineViewService>()?.diagramPanel?.currentPlantUml
        e.presentation.isEnabled = source != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
