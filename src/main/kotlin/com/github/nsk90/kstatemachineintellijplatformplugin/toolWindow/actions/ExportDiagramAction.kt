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
import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import java.io.File
import java.io.FileOutputStream

class ExportDiagramAction : AnAction(
    "Export Diagram",
    "Save the rendered state diagram as PNG or SVG",
    AllIcons.ToolbarDecorator.Export,
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val panel = project.service<StateMachineViewService>().diagramPanel ?: return
        val source = panel.currentPlantUml ?: return

        val descriptor = FileSaverDescriptor(
            "Export State Diagram",
            "Choose where to save the diagram (.png or .svg) — file format taken from the extension you type",
        )
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(project.guessProjectDir(), defaultFilename(panel.currentSyntax))
            ?: return

        val target = wrapper.file
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                when (panel.currentSyntax) {
                    DiagramSyntax.PLANTUML -> exportPlantUml(target, source)
                    DiagramSyntax.MERMAID -> exportMermaid(project, target, panel.currentMermaidSvg)
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

    private fun exportPlantUml(target: File, source: String) {
        val format = when (target.extension.lowercase()) {
            "svg" -> FileFormat.SVG
            else -> FileFormat.PNG
        }
        FileOutputStream(target).use { out ->
            SourceStringReader(source).outputImage(out, FileFormatOption(format))
        }
    }

    private fun exportMermaid(project: Project, target: File, svg: String?) {
        // Mermaid currently exports as SVG — that's what the in-browser
        // rendering already produced. PNG-from-Mermaid would need an SVG→PNG
        // rasterizer (Apache Batik, ~3 MB additional dep) and is intentionally
        // out of scope for this iteration.
        if (target.extension.lowercase() != "svg") {
            ApplicationManager.getApplication().invokeLater {
                Messages.showWarningDialog(
                    project,
                    "Mermaid export only supports SVG in this version. " +
                        "Save with a .svg extension, or switch the renderer to PlantUML for PNG output.",
                    "Export Diagram",
                )
            }
            throw IllegalArgumentException("Unsupported format for Mermaid: ${target.extension}")
        }
        val markup = svg
            ?: throw IllegalStateException("No rendered Mermaid SVG available yet — wait for the diagram to finish loading")
        target.writeText(markup)
    }

    private fun defaultFilename(syntax: DiagramSyntax): String = when (syntax) {
        DiagramSyntax.PLANTUML -> "state-diagram.png"
        DiagramSyntax.MERMAID -> "state-diagram.svg"
    }

    override fun update(e: AnActionEvent) {
        val source = e.project?.service<StateMachineViewService>()?.diagramPanel?.currentPlantUml
        e.presentation.isEnabled = source != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
