package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import java.io.File

class ExportDiagramAction : AnAction(
    "Export Diagram",
    "Save the rendered state diagram as SVG",
    AllIcons.ToolbarDecorator.Export,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (activeSourceAndSyntax(project) == null) return

        val descriptor = FileSaverDescriptor(
            "Export State Diagram",
            "Save the diagram as .svg — both renderers produce SVG output",
        )
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(project.guessProjectDir(), "state-diagram.svg")
            ?: return

        val target = wrapper.file
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                exportSvg(project, target, activeSvg(project))
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

    private fun exportSvg(project: Project, target: File, svg: String?) {
        if (target.extension.lowercase() != "svg") {
            ApplicationManager.getApplication().invokeLater {
                Messages.showWarningDialog(
                    project,
                    "Diagram export only supports SVG in this version. " +
                        "Save with a .svg extension.",
                    "Export Diagram",
                )
            }
            throw IllegalArgumentException("Unsupported format: ${target.extension}")
        }
        val markup = svg
            ?: throw IllegalStateException("No rendered SVG available yet — wait for the diagram to finish loading")
        target.writeText(markup)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let { activeSourceAndSyntax(it) } != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
