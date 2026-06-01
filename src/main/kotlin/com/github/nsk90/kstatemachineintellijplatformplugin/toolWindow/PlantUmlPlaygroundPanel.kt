package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.github.nsk90.kstatemachineintellijplatformplugin.psi.PlantUmlGenerator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import java.awt.BorderLayout
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Standalone playground tab: an editable PlantUML text area on the bottom, the
 * rendered image on top. Independent of the project-wide file-switching /
 * caret-tracking flow — the user pastes any PlantUML (typically from a
 * runtime export of their state machine) and sees it render here.
 *
 * Re-renders 500 ms after the last keystroke so typing isn't blocked by the
 * renderer.
 */
class PlantUmlPlaygroundPanel {
    private val imageLabel = JBLabel("", SwingConstants.CENTER).apply {
        verticalAlignment = SwingConstants.TOP
        horizontalAlignment = SwingConstants.LEFT
    }
    private val imageContainer = JPanel(BorderLayout()).apply { add(imageLabel, BorderLayout.CENTER) }
    private val imageScroll = JBScrollPane(imageContainer)

    private val sourceArea = JBTextArea().apply {
        isEditable = true
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = IdeBorderFactory.createTitledBorder("PlantUML input (edit to re-render)", false)
        text = buildSampleTemplate()
    }
    private val sourceScroll = JBScrollPane(sourceArea)

    private val splitter = JBSplitter(/* vertical = */ true, /* proportion = */ 0.6f).apply {
        firstComponent = imageScroll
        secondComponent = sourceScroll
        setHonorComponentsMinimumSize(true)
    }

    val component: JComponent get() = splitter

    private val debounceTimer = Timer(DEBOUNCE_MS) { rerender() }.apply { isRepeats = false }

    init {
        sourceArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = debounceTimer.restart()
            override fun removeUpdate(e: DocumentEvent) = debounceTimer.restart()
            override fun changedUpdate(e: DocumentEvent) = debounceTimer.restart()
        })
        // Kick off the first render so the panel doesn't open empty.
        ApplicationManager.getApplication().invokeLater { rerender() }
    }

    private fun rerender() {
        val source = sourceArea.text.trim()
        if (source.isEmpty()) {
            updateImage(null, "Paste PlantUML in the box below — it will render here.")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val image = try {
                val reader = SourceStringReader(source)
                val out = ByteArrayOutputStream()
                reader.outputImage(out, FileFormatOption(FileFormat.PNG))
                ImageIO.read(ByteArrayInputStream(out.toByteArray()))
            } catch (t: Throwable) {
                thisLogger().warn("Playground PlantUML rendering failed", t)
                null
            }
            ApplicationManager.getApplication().invokeLater {
                updateImage(image, if (image == null) "Render failed — check the PlantUML syntax (see IDE log)" else null)
            }
        }
    }

    private fun updateImage(image: BufferedImage?, message: String?) {
        imageLabel.text = message
        imageLabel.icon = image?.let { ImageIcon(it) }
        imageContainer.revalidate()
        imageContainer.repaint()
    }

    /**
     * Initial sample shown when the Playground first opens. Includes the same
     * dark-theme skinparam block the Diagram tab uses (via
     * [PlantUmlGenerator.darkThemeSkinparams]) when the IDE is in a dark
     * theme, so the demo renders in the right palette out of the box. Once
     * the user starts editing, their content is preserved as-is — we don't
     * re-inject anything.
     */
    private fun buildSampleTemplate(): String = buildString {
        appendLine("@startuml")
        appendLine("!pragma layout smetana")
        appendLine("top to bottom direction")
        if (!JBColor.isBright()) {
            appendLine(PlantUmlGenerator.darkThemeSkinparams())
        }
        appendLine()
        appendLine("state TrafficLight {")
        appendLine("    [*] --> Red")
        appendLine("    Red --> Yellow : tick")
        appendLine("    Yellow --> Green : tick")
        appendLine("    Green --> Red : tick")
        appendLine("}")
        appendLine("@enduml")
    }

    companion object {
        private const val DEBOUNCE_MS = 500
    }
}
