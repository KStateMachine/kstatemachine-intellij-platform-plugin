package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.github.nsk90.kstatemachineintellijplatformplugin.psi.DiagramSyntax
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
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
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

private const val PLAYGROUND_SYNTAX_PREF_KEY = "ksm.playground.syntax"
private const val PLANTUML_CARD = "plantuml"
private const val MERMAID_CARD = "mermaid"

/**
 * Standalone scratchpad: an editable text area on the bottom, the rendered
 * diagram on top. User pastes PlantUML or Mermaid (typically a runtime
 * export of a state machine) and sees it render here. Independent of the
 * project-wide file-switching / caret-tracking flow.
 *
 * Re-renders 500 ms after the last keystroke so typing isn't blocked.
 * Renderer choice and sample template follow the renderer combobox at the
 * top, persisted between sessions via PropertiesComponent.
 *
 * Both renderers are now JCEF-based (TeaVM PlantUML + Viz.js, and Mermaid).
 */
class PlantUmlPlaygroundPanel {

    private val plantUmlRenderer = PlantUmlJsRenderer()
    private val mermaidRenderer = MermaidRenderer()

    private val imageCards = CardLayout()
    private val imageArea = JPanel(imageCards).apply {
        add(plantUmlRenderer.component, PLANTUML_CARD)
        add(mermaidRenderer.component, MERMAID_CARD)
    }

    @Volatile
    private var currentSyntax: DiagramSyntax = loadPersistedSyntax()

    private val sourceArea = JBTextArea().apply {
        isEditable = true
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = IdeBorderFactory.createTitledBorder(borderTitle(currentSyntax), false)
        text = buildSampleTemplate(currentSyntax)
    }
    private val sourceScroll = JBScrollPane(sourceArea)

    private val splitter = JBSplitter(/* vertical = */ true, /* proportion = */ 0.6f).apply {
        firstComponent = imageArea
        secondComponent = sourceScroll
        setHonorComponentsMinimumSize(true)
    }

    private val syntaxSelector = ComboBox(DiagramSyntax.values()).apply { selectedItem = currentSyntax }

    private val syntaxSelectorListener = ActionListener {
        val newSyntax = syntaxSelector.selectedItem as? DiagramSyntax ?: return@ActionListener
        if (newSyntax == currentSyntax) return@ActionListener

        // If the user's text still matches the previously-loaded sample, swap
        // to the new syntax's sample. If they've edited it, leave their work
        // alone — they may be intentionally rendering their own content.
        val previousSample = buildSampleTemplate(currentSyntax)
        val replaceSample = sourceArea.text.trim() == previousSample.trim()

        currentSyntax = newSyntax
        PropertiesComponent.getInstance().setValue(PLAYGROUND_SYNTAX_PREF_KEY, newSyntax.name)
        sourceArea.border = IdeBorderFactory.createTitledBorder(borderTitle(newSyntax), false)
        if (replaceSample) {
            sourceArea.text = buildSampleTemplate(newSyntax)
            sourceArea.caretPosition = 0
        }
        // Defer the imageArea card swap until the new renderer is ready,
        // so the user sees a single direct transition rather than a flash
        // through the gray cover panel. If the source is empty we'd show
        // a placeholder (no ready signal will fire) — swap right away.
        val source = sourceArea.text.trim()
        if (source.isEmpty()) {
            applyCard(newSyntax)
            rerender()
        } else {
            val newRenderer = when (newSyntax) {
                DiagramSyntax.PLANTUML -> plantUmlRenderer
                DiagramSyntax.MERMAID -> mermaidRenderer
            }
            newRenderer.runOnNextReady {
                if (currentSyntax == newSyntax) applyCard(newSyntax)
            }
            rerender()
        }
    }

    private val topBar = JPanel(WrapLayout(FlowLayout.LEFT, 4, 2)).apply {
        add(JLabel("Renderer:"))
        add(syntaxSelector)
    }

    private val rootPanel = JPanel(BorderLayout()).apply {
        add(topBar, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
    }

    val component: JComponent get() = rootPanel

    /** The raw diagram source currently in the editor, or null when empty. */
    val currentSource: String? get() = sourceArea.text.takeIf { it.isNotBlank() }

    /** Which renderer (PlantUML or Mermaid) is currently active in the playground. */
    val syntax: DiagramSyntax get() = currentSyntax

    /** SVG from the last completed render, or null if not yet rendered. */
    val currentSvg: String? get() = when (currentSyntax) {
        DiagramSyntax.PLANTUML -> plantUmlRenderer.currentSvg
        DiagramSyntax.MERMAID -> mermaidRenderer.currentSvg
    }

    private val debounceTimer = Timer(DEBOUNCE_MS) { rerender() }.apply { isRepeats = false }

    init {
        applyCard(currentSyntax)
        syntaxSelector.addActionListener(syntaxSelectorListener)
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
        val dark = !JBColor.isBright()
        if (source.isEmpty()) {
            val msg = "Paste ${currentSyntax.displayName} in the box below — it will render here."
            when (currentSyntax) {
                DiagramSyntax.PLANTUML -> plantUmlRenderer.showPlaceholder(msg)
                DiagramSyntax.MERMAID -> mermaidRenderer.showPlaceholder(msg)
            }
            return
        }
        when (currentSyntax) {
            DiagramSyntax.PLANTUML -> plantUmlRenderer.render(source, dark)
            DiagramSyntax.MERMAID -> mermaidRenderer.render(source, dark)
        }
    }

    private fun applyCard(syntax: DiagramSyntax) {
        ApplicationManager.getApplication().invokeLater {
            val name = if (syntax == DiagramSyntax.PLANTUML) PLANTUML_CARD else MERMAID_CARD
            imageCards.show(imageArea, name)
        }
    }

    private fun borderTitle(syntax: DiagramSyntax): String =
        "${syntax.displayName} input (edit to re-render)"

    /**
     * Initial sample for the chosen [syntax]. Dark theme is now applied by
     * the renderer (PlantUML's TeaVM build takes `{dark: true}`; Mermaid uses
     * `%%{init: {'theme':'dark'}}%%`), so the PlantUML sample no longer
     * embeds the old skinparam block.
     */
    private fun buildSampleTemplate(syntax: DiagramSyntax): String = when (syntax) {
        DiagramSyntax.PLANTUML -> buildString {
            appendLine("@startuml")
            appendLine("top to bottom direction")
            appendLine()
            appendLine("state TrafficLight {")
            appendLine("    [*] --> Red")
            appendLine("    Red --> Yellow : tick")
            appendLine("    Yellow --> Green : tick")
            appendLine("    Green --> Red : tick")
            appendLine("}")
            appendLine("@enduml")
        }
        DiagramSyntax.MERMAID -> buildString {
            if (!JBColor.isBright()) appendLine("%%{init: {'theme': 'dark'}}%%")
            appendLine("stateDiagram-v2")
            appendLine("    direction TB")
            appendLine("    state TrafficLight {")
            appendLine("        [*] --> Red")
            appendLine("        Red --> Yellow : tick")
            appendLine("        Yellow --> Green : tick")
            appendLine("        Green --> Red : tick")
            appendLine("    }")
        }
    }

    private fun loadPersistedSyntax(): DiagramSyntax {
        val raw = PropertiesComponent.getInstance().getValue(PLAYGROUND_SYNTAX_PREF_KEY)
            ?: return DiagramSyntax.PLANTUML
        return runCatching { DiagramSyntax.valueOf(raw) }.getOrDefault(DiagramSyntax.PLANTUML)
    }

    companion object {
        private const val DEBOUNCE_MS = 500
    }
}
