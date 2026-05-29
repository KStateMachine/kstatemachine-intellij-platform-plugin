package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.psi.PlantUmlGenerator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.JBSplitter
import com.intellij.ui.IdeBorderFactory
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

class StateMachineDiagramPanel {
    private val imageLabel = JBLabel("", SwingConstants.CENTER).apply {
        verticalAlignment = SwingConstants.TOP
        horizontalAlignment = SwingConstants.LEFT
    }
    private val imageContainer = JPanel(BorderLayout()).apply { add(imageLabel, BorderLayout.CENTER) }
    private val imageScroll = JBScrollPane(imageContainer)

    private val sourceArea = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = IdeBorderFactory.createTitledBorder("PlantUML source", false)
    }
    private val sourceScroll = JBScrollPane(sourceArea)

    private val splitter = JBSplitter(/* vertical = */ true, /* proportion = */ 0.7f).apply {
        firstComponent = imageScroll
        secondComponent = sourceScroll
        setHonorComponentsMinimumSize(true)
    }

    val component: JComponent get() = splitter

    /** Last successfully generated PlantUML source. Used by Copy / Export actions. */
    @Volatile
    var currentPlantUml: String? = null
        private set

    /** Last rendered image (cached for Export actions). */
    @Volatile
    var currentImage: BufferedImage? = null
        private set

    private var lastRenderedSource: String? = null

    fun showPlaceholder(message: String) {
        currentPlantUml = null
        currentImage = null
        lastRenderedSource = null
        updateLabel(message)
        updateSourceArea("")
    }

    fun render(machines: List<StateMachine>) {
        if (machines.isEmpty()) {
            showPlaceholder("No state machines in this file")
            return
        }
        // Render only the first machine for now. Multi-machine files get a hint.
        val machine = machines.first()
        val source = PlantUmlGenerator.render(machine)
        currentPlantUml = source
        updateSourceArea(source)

        if (source == lastRenderedSource && currentImage != null) {
            updateLabel(null)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            val image = try {
                renderPng(source)
            } catch (t: Throwable) {
                thisLogger().warn("PlantUML rendering failed", t)
                null
            }
            ApplicationManager.getApplication().invokeLater {
                currentImage = image
                lastRenderedSource = source
                if (image == null) {
                    updateLabel("Failed to render diagram — see IDE log for details")
                } else {
                    val suffix = if (machines.size > 1) {
                        " (showing first of ${machines.size} machines)"
                    } else ""
                    imageLabel.text = null
                    imageLabel.icon = ImageIcon(image)
                    imageLabel.toolTipText = "${machine.name}$suffix"
                    imageContainer.revalidate()
                    imageContainer.repaint()
                }
            }
        }
    }

    private fun renderPng(source: String): BufferedImage? {
        val reader = SourceStringReader(source)
        val out = ByteArrayOutputStream()
        reader.outputImage(out, FileFormatOption(FileFormat.PNG))
        return ImageIO.read(ByteArrayInputStream(out.toByteArray()))
    }

    private fun updateLabel(message: String?) {
        ApplicationManager.getApplication().invokeLater {
            imageLabel.icon = null
            imageLabel.text = message
            imageContainer.revalidate()
            imageContainer.repaint()
        }
    }

    private fun updateSourceArea(text: String) {
        ApplicationManager.getApplication().invokeLater {
            sourceArea.text = text
            sourceArea.caretPosition = 0
        }
    }
}
