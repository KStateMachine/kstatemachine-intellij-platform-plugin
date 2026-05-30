package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow.actions

import com.github.nsk90.kstatemachineintellijplatformplugin.services.StateMachineViewService
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import java.io.FileOutputStream

class ExportDiagramAction : AnAction(
    "Export Diagram",
    "Save the rendered state diagram as PNG or SVG",
    AllIcons.ToolbarDecorator.Export,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val source = project.service<StateMachineViewService>().diagramPanel?.currentPlantUml ?: return

        val descriptor = FileSaverDescriptor(
            "Export State Diagram",
            "Choose where to save the diagram (.png or .svg) — file format is taken from the extension you type",
        )
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(project.guessProjectDir(), "state-diagram.png")
            ?: return

        val target = wrapper.file
        val format = when (target.extension.lowercase()) {
            "svg" -> FileFormat.SVG
            else -> FileFormat.PNG
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                FileOutputStream(target).use { out ->
                    SourceStringReader(source).outputImage(out, FileFormatOption(format))
                }
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(project, "Saved to ${target.absolutePath}", "Export Diagram")
                }
            } catch (t: Throwable) {
                thisLogger().warn("Failed to export diagram to ${target.absolutePath}", t)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Failed to export: ${t.message}", "Export Diagram")
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val source = e.project?.service<StateMachineViewService>()?.diagramPanel?.currentPlantUml
        e.presentation.isEnabled = source != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
