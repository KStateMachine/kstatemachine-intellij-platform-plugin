package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow.actions

import com.github.nsk90.kstatemachineintellijplatformplugin.psi.DiagramSyntax
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
        val panel = project.service<StateMachineViewService>().diagramPanel ?: return
        if (panel.currentPlantUml == null) return

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
                val svg = when (panel.currentSyntax) {
                    DiagramSyntax.PLANTUML -> panel.currentPlantUmlSvg
                    DiagramSyntax.MERMAID -> panel.currentMermaidSvg
                }
                exportSvg(project, target, svg)
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
        // PNG-from-JCEF would need viewport screenshot plumbing — out of scope
        // since the user explicitly chose SVG-only when we dropped plantuml-mit.
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
        val source = e.project?.service<StateMachineViewService>()?.diagramPanel?.currentPlantUml
        e.presentation.isEnabled = source != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
