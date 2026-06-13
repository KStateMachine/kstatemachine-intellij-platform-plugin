package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.psi.DiagramSyntax
import com.github.nsk90.kstatemachineintellijplatformplugin.psi.PlantUmlGenerator
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ActionListener
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

private const val SYNTAX_PREF_KEY = "ksm.diagram.syntax"
private const val ORTHO_LINES_PREF_KEY = "ksm.diagram.linetype.ortho"
private const val PLANTUML_CARD = "plantuml"
private const val MERMAID_CARD = "mermaid"

class StateMachineDiagramPanel(private val project: Project) {

    // --- Rendering surfaces (both JCEF-backed: TeaVM PlantUML + Viz.js, and Mermaid) ---
    private val plantUmlRenderer = PlantUmlJsRenderer()
    private val mermaidRenderer = MermaidRenderer()

    // --- Image area: card layout to swap between PlantUML and Mermaid ---
    private val imageCards = CardLayout()
    private val imageArea = JPanel(imageCards).apply {
        add(plantUmlRenderer.component, PLANTUML_CARD)
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

    // True while we're mutating the dropdown in response to a tree selection
    // (which already navigated the editor). Stops the dropdown listener from
    // navigating a second time and stealing focus from whatever the tree did.
    @Volatile
    private var suppressNavigationOnSelect = false

    private val machineSelectorListener = ActionListener {
        renderSelected()
        if (!suppressNavigationOnSelect) navigateToSelectedMachine()
    }
    private val syntaxSelectorListener = ActionListener {
        val newSyntax = syntaxSelector.selectedItem as? DiagramSyntax ?: return@ActionListener
        if (newSyntax == currentSyntax) return@ActionListener
        currentSyntax = newSyntax
        PropertiesComponent.getInstance().setValue(SYNTAX_PREF_KEY, newSyntax.name)
        orthoLinesCheckBox.isVisible = (newSyntax == DiagramSyntax.PLANTUML)
        applyCard(newSyntax)
        // Force a re-render (cache key now mismatches because syntax changed).
        lastRenderedSource = null
        renderSelected()
    }

    private val orthoLinesCheckBox = JCheckBox("Ortho lines").apply {
        isSelected = PropertiesComponent.getInstance().getBoolean(ORTHO_LINES_PREF_KEY, false)
        toolTipText = "Add 'skinparam linetype ortho' to the PlantUML diagram"
    }

    private val topBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        add(JLabel("Renderer:"))
        add(syntaxSelector)
        add(machineSelector)
        add(orthoLinesCheckBox)
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

    /** The renderer currently driving the diagram tab. */
    @Volatile
    var currentSyntax: DiagramSyntax = loadPersistedSyntax()
        private set

    /** SVG markup from the last PlantUML render — used by the Export action. */
    val currentPlantUmlSvg: String? get() = plantUmlRenderer.currentSvg

    /** SVG markup from the last Mermaid render — used by the Export action. */
    val currentMermaidSvg: String? get() = mermaidRenderer.currentSvg

    private var lastRenderedSource: String? = null

    @Volatile
    private var currentMachines: List<StateMachine> = emptyList()

    init {
        machineSelector.addActionListener(machineSelectorListener)
        syntaxSelector.selectedItem = currentSyntax
        syntaxSelector.addActionListener(syntaxSelectorListener)
        orthoLinesCheckBox.isVisible = (currentSyntax == DiagramSyntax.PLANTUML)
        orthoLinesCheckBox.addActionListener {
            PropertiesComponent.getInstance().setValue(ORTHO_LINES_PREF_KEY, orthoLinesCheckBox.isSelected)
            lastRenderedSource = null
            renderSelected()
        }
        applyCard(currentSyntax)
        // Remove the global AWT mouse listeners when this panel is removed from
        // the Swing hierarchy (tool window closed / project closed).
        rootPanel.addHierarchyListener { e ->
            if (e.changeFlags and java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L
                && !rootPanel.isDisplayable
            ) {
                plantUmlRenderer.dispose()
                mermaidRenderer.dispose()
            }
        }
    }

    fun showPlaceholder(message: String) {
        currentMachines = emptyList()
        rebuildSelector(emptyList())
        currentPlantUml = null
        lastRenderedSource = null
        when (currentSyntax) {
            DiagramSyntax.PLANTUML -> plantUmlRenderer.showPlaceholder(message)
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
        suppressNavigationOnSelect = true
        try {
            machineSelector.selectedIndex = itemIdx
        } finally {
            suppressNavigationOnSelect = false
        }
    }

    /**
     * Mirror what the tree does on selection: open the source file and place the
     * caret on the machine's `createStateMachine(...)` call. `requestFocus = false`
     * keeps focus on the diagram tab so the dropdown stays usable for arrow-key
     * cycling — same UX pattern the tree uses.
     */
    private fun navigateToSelectedMachine() {
        val selected = (machineSelector.selectedItem as? MachineEntry)?.index ?: return
        val machine = currentMachines.getOrNull(selected) ?: return
        val pointer = machine.pointer ?: return
        val element = pointer.element ?: return
        val vf = pointer.virtualFile ?: return
        OpenFileDescriptor(project, vf, element.textRange.startOffset).navigate(false)
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
        var source = PlantUmlGenerator.render(machine, darkTheme = dark, syntax = currentSyntax)
        if (currentSyntax == DiagramSyntax.PLANTUML && orthoLinesCheckBox.isSelected) {
            source = injectAfterFirstLine(source, "skinparam linetype ortho")
        }
        currentPlantUml = source
        updateSourceArea(source)

        if (source == lastRenderedSource) return
        lastRenderedSource = source

        when (currentSyntax) {
            DiagramSyntax.PLANTUML -> plantUmlRenderer.render(source, dark)
            DiagramSyntax.MERMAID -> mermaidRenderer.render(source, dark)
        }
    }

    private fun injectAfterFirstLine(source: String, line: String): String {
        val nl = source.indexOf('\n')
        if (nl < 0) return source
        return source.substring(0, nl + 1) + line + "\n" + source.substring(nl + 1)
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
