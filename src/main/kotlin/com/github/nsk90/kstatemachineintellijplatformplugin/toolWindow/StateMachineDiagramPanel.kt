package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.psi.PlantUmlGenerator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.ComboBox
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
     * Switch the dropdown to the entry for [machine]. Used by the tree panel
     * so that selecting a node inside machine B in the Structure tab
     * automatically shows machine B in the Diagram tab when the user
     * switches over. No-op if the machine isn't in the current list or is
     * already selected.
     */
    fun selectMachine(machine: StateMachine) {
        val targetIdx = currentMachines.indexOfFirst { it === machine }
        if (targetIdx < 0) return
        val currentEntry = machineSelector.selectedItem as? MachineEntry
        if (currentEntry?.index == targetIdx) return
        val itemIdx = (0 until machineSelector.itemCount).firstOrNull {
            machineSelector.getItemAt(it).index == targetIdx
        } ?: return
        machineSelector.selectedIndex = itemIdx
    }

    /**
     * Repopulates the machine dropdown. Labels match the tree's convention
     * exactly so the user can read `StateMachine #2` in the tree and find the
     * same entry in the dropdown without guessing. Keeps the user's selection
     * across re-renders by matching on the rendered label.
     */
    private fun rebuildSelector(machines: List<StateMachine>) {
        val previousLabel = (machineSelector.selectedItem as? MachineEntry)?.label
        // Suppress fire-on-change while we mutate the model.
        machineSelector.removeActionListener(selectorListener)
        try {
            machineSelector.removeAllItems()
            // Per-file index of unnamed machines, mirroring the tree's
            // computeUnnamedIndices top-level counter.
            var unnamedCounter = 0
            machines.forEachIndexed { idx, m ->
                val label = if (m.name.isMachineUnnamed()) {
                    unnamedCounter++
                    "StateMachine #$unnamedCounter"
                } else {
                    m.name.unquoteName()
                }
                machineSelector.addItem(MachineEntry(label, idx))
            }
            // Hide the chooser entirely when there's nothing to choose between.
            machineSelector.isVisible = machines.size > 1
            if (machines.isNotEmpty()) {
                val matchIdx = (0 until machineSelector.itemCount).firstOrNull { i ->
                    machineSelector.getItemAt(i).label == previousLabel
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
        // Read the LaF flag at render time so a theme change between renders
        // produces a different source string and naturally invalidates the
        // image cache (no separate listener required).
        val source = PlantUmlGenerator.render(machine, darkTheme = !JBColor.isBright())
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
                    imageLabel.toolTipText = (machineSelector.selectedItem as? MachineEntry)?.label
                        ?: machine.name.unquoteName()
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

    /**
     * Combobox row. `label` is the display string already in tree-matching
     * form (`Hero` for named, `StateMachine #2` for unnamed). `index` is the
     * machine's position in the source list so [renderSelected] can look it
     * up again.
     */
    private data class MachineEntry(val label: String, val index: Int) {
        override fun toString(): String = label
    }
}

/** Strip a single pair of surrounding double quotes — that's how string-literal names arrive from the parser. */
private fun String.unquoteName(): String =
    if (length >= 2 && startsWith('"') && endsWith('"')) substring(1, length - 1) else this

/** Matches the parser's `<unnamed>` sentinel, plain blank, or the literal text `null`. */
private fun String.isMachineUnnamed(): Boolean {
    val u = unquoteName()
    return u.isBlank() || u == "null" || u == "<unnamed>"
}
