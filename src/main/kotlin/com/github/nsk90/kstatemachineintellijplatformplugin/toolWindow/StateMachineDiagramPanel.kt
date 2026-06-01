package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.psi.DiagramSyntax
import com.github.nsk90.kstatemachineintellijplatformplugin.psi.PlantUmlGenerator
import com.intellij.ide.util.PropertiesComponent
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
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ActionListener
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

private const val SYNTAX_PREF_KEY = "ksm.diagram.syntax"
private const val PLANTUML_CARD = "plantuml"
private const val MERMAID_CARD = "mermaid"

class StateMachineDiagramPanel {

    // --- PlantUML rendering surface ---
    private val imageLabel = JBLabel("", SwingConstants.CENTER).apply {
        verticalAlignment = SwingConstants.TOP
        horizontalAlignment = SwingConstants.LEFT
    }
    private val imageContainer = JPanel(BorderLayout()).apply { add(imageLabel, BorderLayout.CENTER) }
    private val imageScroll = JBScrollPane(imageContainer)

    // --- Mermaid rendering surface ---
    private val mermaidRenderer = MermaidRenderer()

    // --- Image area: card layout to swap between PlantUML and Mermaid ---
    private val imageCards = CardLayout()
    private val imageArea = JPanel(imageCards).apply {
        add(imageScroll, PLANTUML_CARD)
        add(mermaidRenderer.component, MERMAID_CARD)
    }

    // --- Source pane ---
    private val sourceArea = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = IdeBorderFactory.createTitledBorder("Diagram source", false)
    }
    private val sourceScroll = JBScrollPane(sourceArea)

    private val splitter = JBSplitter(/* vertical = */ true, /* proportion = */ 0.7f).apply {
        firstComponent = imageArea
        secondComponent = sourceScroll
        setHonorComponentsMinimumSize(true)
    }

    // --- Top toolbar ---
    private val machineSelector = ComboBox<MachineEntry>()
    private val syntaxSelector = ComboBox(DiagramSyntax.values())
    private val machineSelectorListener = ActionListener { renderSelected() }
    private val syntaxSelectorListener = ActionListener {
        val newSyntax = syntaxSelector.selectedItem as? DiagramSyntax ?: return@ActionListener
        if (newSyntax == currentSyntax) return@ActionListener
        currentSyntax = newSyntax
        PropertiesComponent.getInstance().setValue(SYNTAX_PREF_KEY, newSyntax.name)
        applyCard(newSyntax)
        // Force a re-render (cache key now mismatches because syntax changed).
        lastRenderedSource = null
        renderSelected()
    }

    private val topBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        add(JLabel("Renderer:"))
        add(syntaxSelector)
        add(machineSelector)
    }

    private val rootPanel = JPanel(BorderLayout()).apply {
        add(topBar, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
    }

    val component: JComponent get() = rootPanel

    /** Last successfully generated source (PlantUML or Mermaid). Used by Copy / Export. */
    @Volatile
    var currentPlantUml: String? = null
        private set

    /** Last rendered PlantUML image (cached for Export). Null when current renderer is Mermaid. */
    @Volatile
    var currentImage: BufferedImage? = null
        private set

    /** The renderer currently driving the diagram tab. */
    @Volatile
    var currentSyntax: DiagramSyntax = loadPersistedSyntax()
        private set

    /** SVG markup from the last Mermaid render — used by the Export action. */
    val currentMermaidSvg: String? get() = mermaidRenderer.currentSvg

    private var lastRenderedSource: String? = null

    @Volatile
    private var currentMachines: List<StateMachine> = emptyList()

    init {
        machineSelector.addActionListener(machineSelectorListener)
        syntaxSelector.selectedItem = currentSyntax
        syntaxSelector.addActionListener(syntaxSelectorListener)
        applyCard(currentSyntax)
    }

    fun showPlaceholder(message: String) {
        currentMachines = emptyList()
        rebuildSelector(emptyList())
        currentPlantUml = null
        currentImage = null
        lastRenderedSource = null
        when (currentSyntax) {
            DiagramSyntax.PLANTUML -> updateLabel(message)
            DiagramSyntax.MERMAID -> mermaidRenderer.showPlaceholder(message)
        }
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
     * automatically shows machine B in the Diagram tab.
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

    private fun rebuildSelector(machines: List<StateMachine>) {
        val previousLabel = (machineSelector.selectedItem as? MachineEntry)?.label
        machineSelector.removeActionListener(machineSelectorListener)
        try {
            machineSelector.removeAllItems()
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
            machineSelector.isVisible = machines.size > 1
            if (machines.isNotEmpty()) {
                val matchIdx = (0 until machineSelector.itemCount).firstOrNull { i ->
                    machineSelector.getItemAt(i).label == previousLabel
                } ?: 0
                machineSelector.selectedIndex = matchIdx
            }
        } finally {
            machineSelector.addActionListener(machineSelectorListener)
        }
    }

    private fun renderSelected() {
        if (currentMachines.isEmpty()) return
        val selected = (machineSelector.selectedItem as? MachineEntry)?.index ?: 0
        val machine = currentMachines.getOrNull(selected) ?: return
        renderMachine(machine)
    }

    private fun renderMachine(machine: StateMachine) {
        val dark = !JBColor.isBright()
        val source = PlantUmlGenerator.render(machine, darkTheme = dark, syntax = currentSyntax)
        currentPlantUml = source
        updateSourceArea(source)

        if (source == lastRenderedSource && (currentImage != null || currentSyntax == DiagramSyntax.MERMAID)) {
            return
        }
        lastRenderedSource = source

        when (currentSyntax) {
            DiagramSyntax.PLANTUML -> renderPlantUml(machine, source)
            DiagramSyntax.MERMAID -> mermaidRenderer.render(source, dark)
        }
    }

    private fun renderPlantUml(machine: StateMachine, source: String) {
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

    private fun applyCard(syntax: DiagramSyntax) {
        ApplicationManager.getApplication().invokeLater {
            val name = if (syntax == DiagramSyntax.PLANTUML) PLANTUML_CARD else MERMAID_CARD
            imageCards.show(imageArea, name)
        }
    }

    private fun loadPersistedSyntax(): DiagramSyntax {
        val raw = PropertiesComponent.getInstance().getValue(SYNTAX_PREF_KEY)
            ?: return DiagramSyntax.PLANTUML
        return runCatching { DiagramSyntax.valueOf(raw) }.getOrDefault(DiagramSyntax.PLANTUML)
    }

    /** Combobox row. `label` is the display string already in tree-matching form. */
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
