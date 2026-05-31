package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.psi.PlantUmlGenerator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import net.sourceforge.plantuml.FileFormat
import net.sourceforge.plantuml.FileFormatOption
import net.sourceforge.plantuml.SourceStringReader
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.ActionListener
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

    private val machineSelector = ComboBox<MachineEntry>()
    private val selectorListener = ActionListener { renderSelected() }

    private val rootPanel = JPanel(BorderLayout()).apply {
        add(machineSelector, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
    }

    val component: JComponent get() = rootPanel

    /** Last successfully generated PlantUML source. Used by Copy / Export actions. */
    @Volatile
    var currentPlantUml: String? = null
        private set

    /** Last rendered image (cached for Export actions). */
    @Volatile
    var currentImage: BufferedImage? = null
        private set

    private var lastRenderedSource: String? = null

    @Volatile
    private var currentMachines: List<StateMachine> = emptyList()

    init {
        machineSelector.addActionListener(selectorListener)
    }

    fun showPlaceholder(message: String) {
        currentMachines = emptyList()
        rebuildSelector(emptyList())
        currentPlantUml = null
        currentImage = null
        lastRenderedSource = null
        updateLabel(message)
        updateSourceArea("")
    }

    fun render(machines: List<StateMachine>) {
        currentMachines = machines
        if (machines.isEmpty()) {
            showPlaceholder("No state machines in this file")
            return
        }
        rebuildSelector(machines)
        renderSelected()
    }

    /**
     * Repopulates the machine dropdown. Keeps the user's selection across
     * re-renders by matching on machine name — so a live edit doesn't kick
     * them back to machine #1.
     */
    private fun rebuildSelector(machines: List<StateMachine>) {
        val previousName = (machineSelector.selectedItem as? MachineEntry)?.machineName
        // Suppress fire-on-change while we mutate the model.
        machineSelector.removeActionListener(selectorListener)
        try {
            machineSelector.removeAllItems()
            machines.forEachIndexed { idx, m -> machineSelector.addItem(MachineEntry(m.name.cleanName(), idx)) }
            // Hide the chooser entirely when there's nothing to choose between.
            machineSelector.isVisible = machines.size > 1
            if (machines.isNotEmpty()) {
                val matchIdx = (0 until machineSelector.itemCount).firstOrNull { i ->
                    machineSelector.getItemAt(i).machineName == previousName
                } ?: 0
                machineSelector.selectedIndex = matchIdx
            }
        } finally {
            machineSelector.addActionListener(selectorListener)
        }
    }

    private fun renderSelected() {
        if (currentMachines.isEmpty()) return
        val selected = (machineSelector.selectedItem as? MachineEntry)?.index ?: 0
        val machine = currentMachines.getOrNull(selected) ?: return
        renderMachine(machine)
    }

    private fun renderMachine(machine: StateMachine) {
        val source = PlantUmlGenerator.render(machine)
        currentPlantUml = source
        updateSourceArea(source)

        if (source == lastRenderedSource && currentImage != null) {
            // Identical input — the existing icon is still valid.
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
                if (image == null) {
                    currentImage = null
                    updateLabel("Failed to render diagram — see IDE log for details")
                } else {
                    currentImage = image
                    lastRenderedSource = source
                    imageLabel.text = null
                    imageLabel.icon = ImageIcon(image)
                    imageLabel.toolTipText = machine.name.cleanName()
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

    /** Combobox row — `1. Hero` style. The numeric prefix disambiguates same-named machines. */
    private data class MachineEntry(val machineName: String, val index: Int) {
        override fun toString(): String = "${index + 1}. $machineName"
    }
}

private fun String.cleanName(): String {
    val unquoted = if (length >= 2 && startsWith('"') && endsWith('"')) substring(1, length - 1) else this
    return if (unquoted.isBlank() || unquoted == "null" || unquoted == "<unnamed>") "(unnamed)" else unquoted
}
