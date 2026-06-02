package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.github.nsk90.kstatemachineintellijplatformplugin.model.Guard
import com.github.nsk90.kstatemachineintellijplatformplugin.model.State
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateKind
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.model.Transition
import com.github.nsk90.kstatemachineintellijplatformplugin.services.StateMachineViewService
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.IdentityHashMap
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class StateMachineTreePanel(private val project: Project) {
    private val rootNode = DefaultMutableTreeNode("State machines")
    private val treeModel = DefaultTreeModel(rootNode)
    private val cellRenderer = StateMachineCellRenderer()
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = this@StateMachineTreePanel.cellRenderer
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }
    private val scrollPane = JBScrollPane(tree)

    // Prevents the tree → editor navigation from firing when we are setting
    // the selection programmatically in response to a caret move.
    private var suppressSelectionEvents = false

    val component: JComponent get() = scrollPane

    init {
        tree.addTreeSelectionListener {
            if (suppressSelectionEvents) return@addTreeSelectionListener
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            // Mirror the selection into the diagram dropdown — if the user
            // then switches tabs, the Diagram already shows the machine they
            // were just exploring in the tree.
            node.topLevelMachine()?.let { machine ->
                project.service<StateMachineViewService>().diagramPanel?.selectMachine(machine)
            }
            val pointer = node.pointer() ?: return@addTreeSelectionListener
            navigateToPointer(pointer)
        }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) maybeShowTransitionPopup(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) maybeShowTransitionPopup(e)
            }
        })
    }

    /**
     * Right-click handler for rows that point at another state. Shows a popup
     * with "Navigate to target state" when:
     *   - the row is a `Transition` and its `targetState` resolves to a single
     *     tree node, OR
     *   - the row is a `choiceState`-family `State` whose lambda body resolves
     *     to a single tree node.
     * Otherwise no popup — nothing actionable.
     */
    private fun maybeShowTransitionPopup(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        // Scope the search to the right-clicked node's enclosing state machine
        // — when there are multiple machines in the file they often share state
        // names, and a tree-wide search would jump to the first match in some
        // unrelated machine.
        val searchRoot = node.enclosingMachineNode() ?: rootNode
        val targetNode = when (val data = node.userObject) {
            is Transition -> findTargetStateNode(data, searchRoot)
            is State -> findChoiceTargetNode(data, searchRoot)
            else -> null
        } ?: return

        // Select the right-clicked row first so the popup visibly belongs to it.
        // Suppress the selection listener so we don't open the source location
        // of the row itself — the menu click will jump to the *target* instead.
        suppressSelectionEvents = true
        try {
            tree.selectionPath = path
        } finally {
            suppressSelectionEvents = false
        }

        JPopupMenu().apply {
            add(JMenuItem("Navigate to target state").apply {
                addActionListener { selectNode(targetNode) }
            })
        }.show(tree, e.x, e.y)
    }

    private fun findChoiceTargetNode(
        state: State,
        searchRoot: DefaultMutableTreeNode,
    ): DefaultMutableTreeNode? {
        val raw = state.redirectTarget?.trim() ?: return null
        val target = raw.removeSurrounding("\"").trim()
        if (target.isEmpty()) return null
        return findStateNodeByName(searchRoot, target)
    }

    private fun findTargetStateNode(
        transition: Transition,
        searchRoot: DefaultMutableTreeNode,
    ): DefaultMutableTreeNode? {
        val raw = transition.targetStateName?.trim() ?: return null
        val target = when {
            // transitionOn lambda — `targetState = { someState }`. If the lambda
            // body is a single identifier (optionally dotted, e.g. `Names.RED`),
            // we can resolve it just like a plain reference. Anything with
            // operators / branches / calls (`{ if (…) A else B }`) stays
            // unresolved — there's no single deterministic target.
            raw.startsWith("{") && raw.endsWith("}") -> {
                val body = raw.removeSurrounding("{", "}").trim()
                    .removePrefix("this.").trim()
                if (body.isNotEmpty() && body.all { it.isLetterOrDigit() || it == '_' || it == '.' }) {
                    // Use the last segment for dotted refs like `Names.RED` so
                    // the tree match works against state nodes named `RED`.
                    body.substringAfterLast('.')
                } else {
                    return null
                }
            }
            else -> raw.removeSurrounding("\"").trim()
        }
        if (target.isEmpty()) return null
        return findStateNodeByName(searchRoot, target)
    }

    private fun findStateNodeByName(node: DefaultMutableTreeNode, name: String): DefaultMutableTreeNode? {
        val state = node.userObject as? State
        if (state != null && state.name.unquote().equals(name, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            findStateNodeByName(child, name)?.let { return it }
        }
        return null
    }

    /**
     * Walk up the tree from [this] looking for the closest ancestor (or self)
     * whose user object is a [StateMachine]. Used to scope target-state
     * resolution to the right machine when several sibling machines in the
     * same file share state names.
     */
    private fun DefaultMutableTreeNode.enclosingMachineNode(): DefaultMutableTreeNode? {
        var current: DefaultMutableTreeNode? = this
        while (current != null) {
            if (current.userObject is StateMachine) return current
            current = current.parent as? DefaultMutableTreeNode
        }
        return null
    }

    /**
     * The outermost (top-level) [StateMachine] containing this node — i.e. the
     * one that appears in the diagram tab's dropdown. Walks all the way to the
     * root, recording each StateMachine seen; the last one is the top-level.
     * Used to sync tree selection into the diagram dropdown.
     */
    private fun DefaultMutableTreeNode.topLevelMachine(): StateMachine? {
        var current: DefaultMutableTreeNode? = this
        var outermost: StateMachine? = null
        while (current != null) {
            (current.userObject as? StateMachine)?.let { outermost = it }
            current = current.parent as? DefaultMutableTreeNode
        }
        return outermost
    }

    private fun selectNode(target: DefaultMutableTreeNode) {
        val path = TreePath(target.path)
        tree.expandPath(path.parentPath ?: path)
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
        // Selection event will fire normally, jumping the editor to the target
        // state's declaration — which is exactly the "navigate" the user asked for.
    }

    fun setMachines(machines: List<StateMachine>) {
        cellRenderer.unnamedIndices = computeUnnamedIndices(machines)
        rootNode.removeAllChildren()
        machines.forEach { rootNode.add(buildNode(it)) }
        treeModel.reload()
        expandAll()
    }

    fun clear() {
        rootNode.removeAllChildren()
        treeModel.reload()
    }

    /** Select the smallest tree node whose source range contains [offset]. */
    fun selectNodeForOffset(offset: Int) {
        val match = findSmallestContaining(rootNode, offset) ?: return
        val path = TreePath(match.path)
        suppressSelectionEvents = true
        try {
            tree.expandPath(path.parentPath ?: path)
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        } finally {
            suppressSelectionEvents = false
        }
        // Also sync the diagram dropdown: editor-caret moves are routed
        // through here with the selection listener suppressed (to avoid the
        // navigate-back-to-editor loop), so the tree → diagram sync that
        // normally happens via the selection listener has to be triggered
        // explicitly. Without this, moving the caret into machine B's body
        // while the diagram still shows machine A wouldn't refresh the
        // dropdown — the user would have to switch tabs to notice.
        match.topLevelMachine()?.let { machine ->
            project.service<StateMachineViewService>().diagramPanel?.selectMachine(machine)
        }
    }

    private fun buildNode(state: State): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(state)
        state.states.forEach { node.add(buildNode(it)) }
        state.transitions.forEach { transition ->
            val transitionNode = DefaultMutableTreeNode(transition)
            node.add(transitionNode)
            // Surface the guard expression as a navigable child node so the
            // user can see what the transition is gated on and jump directly
            // to the guard's source — same pattern as state/transition nodes.
            transition.guard?.let { transitionNode.add(DefaultMutableTreeNode(it)) }
        }
        return node
    }

    private fun expandAll() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    private fun navigateToPointer(pointer: SmartPsiElementPointer<out PsiElement>) {
        val element = pointer.element ?: return
        val vf = pointer.virtualFile ?: return
        // requestFocus = false → the editor opens / scrolls to the location, but
        // keyboard focus stays in the tree so arrow-key navigation through the
        // tree keeps working without the editor stealing it on every selection.
        OpenFileDescriptor(project, vf, element.textRange.startOffset).navigate(false)
    }

    private fun findSmallestContaining(
        node: DefaultMutableTreeNode,
        offset: Int,
    ): DefaultMutableTreeNode? {
        val pointer = node.pointer()
        if (pointer != null) {
            val range = pointer.element?.textRange ?: return null
            if (offset !in range.startOffset..range.endOffset) return null
        }
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            val match = findSmallestContaining(child, offset)
            if (match != null) return match
        }
        return if (pointer != null) node else null
    }
}

private fun DefaultMutableTreeNode.pointer(): SmartPsiElementPointer<out PsiElement>? =
    when (val data = userObject) {
        is State -> data.pointer
        is Transition -> data.pointer
        is Guard -> data.pointer
        else -> null
    }

private class StateMachineCellRenderer : ColoredTreeCellRenderer() {
    /** Index assigned to each unnamed model node (per containing machine, 1-based). */
    var unnamedIndices: Map<Any, Int> = emptyMap()

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val data = node.userObject) {
            is StateMachine -> {
                icon = AllIcons.Nodes.Module
                val resolved = data.name.unquote()
                if (resolved.isBlank() || resolved == "null" || resolved == "<unnamed>") {
                    // Unnamed machine — show just "StateMachine" plus an index
                    // when there are multiple unnamed machines in the file.
                    append(indexedTypeLabel("StateMachine", unnamedIndices[data]))
                } else {
                    append("StateMachine ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(resolved)
                }
                // Top-level machines live at tree depth 1 (direct children of the
                // invisible root). Anything deeper is a *nested* machine — flag it
                // explicitly so it stands out from regular state nodes that share
                // the same tree level.
                if (node.level > 1) append("  (machine)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (data.isParallel) append("  (parallel)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                val counts = data.subtreeCounts()
                append("  (${counts.first} states, ${counts.second} transitions)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is State -> {
                icon = data.kind.icon()
                append(displayName(data.name, "State", unnamedIndices[data]))
                val tag = data.kind.label()
                if (tag != null) append("  $tag", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (data.isParallel) append("  (parallel)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (data.dataType != null) append("  <${data.dataType}>", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (data.defaultData != null) append("  defaultData = ${data.defaultData}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (data.redirectTarget != null) {
                    append("  → ${data.redirectTarget.unquote()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            is Transition -> {
                icon = AllIcons.Actions.Forward
                val label = data.transitionLabel(unnamedIndices[data])
                append(label)
                val suffix = buildString {
                    val typeArgs = data.typeArgsDisplay()
                    if (typeArgs != null && !label.contains(typeArgs)) {
                        append("  $typeArgs")
                    }
                    if (!data.targetStateName.isNullOrBlank()) {
                        append("  → ${data.targetStateName.unquote()}")
                    }
                    val tags = data.transitionTags()
                    if (tags.isNotEmpty()) {
                        append("  [${tags.joinToString(", ")}]")
                    }
                }
                if (suffix.isNotEmpty()) {
                    append(suffix, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            is Guard -> {
                icon = AllIcons.Nodes.Padlock
                append("guard ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                // Compact one-liner: the raw expression source can contain
                // line breaks (lambda body across lines). Collapse them so
                // the tree row stays readable; clicking the node jumps to
                // the real, fully-formatted expression in the editor.
                append(data.text.singleLine())
            }
            else -> append(node.toString())
        }
    }
}

private fun String.singleLine(): String =
    replace(Regex("\\s+"), " ").trim()

private fun displayName(rawName: String, typeLabel: String, index: Int?): String {
    val unquoted = rawName.unquote()
    return if (unquoted.isBlank() || unquoted == "null" || unquoted == "<unnamed>") {
        indexedTypeLabel(typeLabel, index)
    } else unquoted
}

private fun Transition.transitionLabel(index: Int?): String {
    val unquoted = name.unquote()
    return when {
        unquoted.isNotBlank() && unquoted != "null" && unquoted != "<unnamed>" -> unquoted
        eventType != null -> "on $eventType"
        else -> indexedTypeLabel("Transition", index)
    }
}

private fun indexedTypeLabel(typeLabel: String, index: Int?): String =
    if (index != null) "$typeLabel #$index" else typeLabel

/** Same definition shared with [PlantUmlGenerator]. */
private fun isUnnamed(rawName: String): Boolean {
    val unquoted = rawName.unquote()
    return unquoted.isBlank() || unquoted == "null" || unquoted == "<unnamed>"
}

/**
 * Walk every machine and assign a 1-based index to each unnamed model node
 * within that machine. Indices are per-machine (so two unnamed states in
 * different machines can both be `#1` — the user disambiguates by which
 * machine subtree they belong to). Top-level unnamed machines are indexed
 * globally across the file.
 *
 * Identity-based lookup (`IdentityHashMap`) so two structurally-equal model
 * objects don't collapse to the same entry.
 */
private fun computeUnnamedIndices(machines: List<StateMachine>): Map<Any, Int> {
    val result = IdentityHashMap<Any, Int>()

    var topMachineCounter = 0
    machines.forEach { m ->
        if (isUnnamed(m.name)) {
            topMachineCounter++
            result[m] = topMachineCounter
        }
        indexMachineContent(m, result)
    }
    return result
}

private fun indexMachineContent(machine: StateMachine, out: IdentityHashMap<Any, Int>) {
    var stateCounter = 0
    var transitionCounter = 0
    var nestedMachineCounter = 0

    fun walk(node: State) {
        for (child in node.states) {
            if (child is StateMachine) {
                if (isUnnamed(child.name)) {
                    nestedMachineCounter++
                    out[child] = nestedMachineCounter
                }
                // Nested machines get their own scope — recurse separately.
                indexMachineContent(child, out)
            } else {
                if (isUnnamed(child.name)) {
                    stateCounter++
                    out[child] = stateCounter
                }
                walk(child)
            }
        }
        for (t in node.transitions) {
            val tname = t.name
            if (tname.isBlank() || tname == "null" || tname == "<unnamed>") {
                transitionCounter++
                out[t] = transitionCounter
            }
        }
    }
    walk(machine)
}

// `<EventType>` or `<EventType, DataType>` for data transitions. Returns null
// when no type information is available.
private fun Transition.typeArgsDisplay(): String? {
    if (eventType == null && dataType == null) return null
    val parts = listOfNotNull(eventType, dataType)
    return "<${parts.joinToString(", ")}>"
}

// Categorical tags shown after a transition. A single transition may carry
// several (e.g. a `dataTransitionOn` with a guard → `[data, dynamic, guarded]`).
//
// `choice` is NOT a transition tag — it belongs to `choiceState` factory calls
// and is rendered as the state's kind label (`(choice)`).
private fun Transition.transitionTags(): List<String> = buildList {
    when (callee) {
        "transitionOn" -> add("dynamic")
        "transitionConditionally" -> add("conditional")
        "dataTransition" -> add("data")
        "dataTransitionOn" -> {
            add("data")
            add("dynamic")
        }
    }
    if (isGuarded) add("guarded")
    // `targetless` only applies to the forms that take an explicit `targetState`
    // argument and can omit it (then the transition is internal). The other
    // forms route their target through a lambda (`transitionOn`'s `targetState = { … }`,
    // `transitionConditionally`'s `direction = { … }`) — a missing
    // `targetStateName` on those means the parser couldn't statically resolve
    // a lambda body, NOT that the transition is targetless.
    val supportsTargetlessSemantics = callee == null || callee == "transition" || callee == "dataTransition"
    if (supportsTargetlessSemantics && targetStateName.isNullOrBlank()) add("targetless")
}

// Argument values come from the PSI as raw expression text — string literals
// arrive with their surrounding quotes ("Jump"). Strip them so the tree shows
// Jump rather than "Jump". Non-string-literal values (variable refs, enum
// constants) don't have surrounding quotes and pass through unchanged.
private fun String.unquote(): String =
    if (length >= 2 && startsWith('"') && endsWith('"')) substring(1, length - 1) else this

// Recursively count substates and transitions under a state.
private fun State.subtreeCounts(): Pair<Int, Int> {
    var states = 0
    var transitions = transitions.size
    states += this.states.size
    this.states.forEach { child ->
        val (s, t) = child.subtreeCounts()
        states += s
        transitions += t
    }
    return states to transitions
}

private fun StateKind.icon() = when (this) {
    StateKind.INITIAL, StateKind.INITIAL_DATA, StateKind.INITIAL_MUTABLE_DATA -> AllIcons.Actions.Execute
    StateKind.FINAL, StateKind.FINAL_DATA, StateKind.FINAL_MUTABLE_DATA -> AllIcons.Actions.Suspend
    StateKind.INITIAL_FINAL, StateKind.INITIAL_FINAL_DATA, StateKind.INITIAL_FINAL_MUTABLE_DATA -> AllIcons.Actions.Suspend
    StateKind.CHOICE, StateKind.CHOICE_DATA -> AllIcons.Vcs.Branch
    StateKind.INITIAL_CHOICE, StateKind.INITIAL_CHOICE_DATA -> AllIcons.Vcs.Branch
    StateKind.HISTORY, StateKind.HISTORY_DEEP -> AllIcons.Vcs.History
    StateKind.STATE, StateKind.DATA, StateKind.MUTABLE_DATA -> AllIcons.Nodes.Class
}

private fun StateKind.label(): String? = when (this) {
    StateKind.STATE -> null
    StateKind.INITIAL -> "(initial)"
    StateKind.FINAL -> "(final)"
    StateKind.INITIAL_FINAL -> "(initial, final)"
    StateKind.DATA -> "(data)"
    StateKind.INITIAL_DATA -> "(initial data)"
    StateKind.FINAL_DATA -> "(final data)"
    StateKind.INITIAL_FINAL_DATA -> "(initial, final data)"
    StateKind.MUTABLE_DATA -> "(mutable data)"
    StateKind.INITIAL_MUTABLE_DATA -> "(initial mutable data)"
    StateKind.FINAL_MUTABLE_DATA -> "(final mutable data)"
    StateKind.INITIAL_FINAL_MUTABLE_DATA -> "(initial, final mutable data)"
    StateKind.CHOICE -> "(choice)"
    StateKind.INITIAL_CHOICE -> "(initial choice)"
    StateKind.CHOICE_DATA -> "(choice data)"
    StateKind.INITIAL_CHOICE_DATA -> "(initial choice data)"
    StateKind.HISTORY -> "(history, shallow)"
    StateKind.HISTORY_DEEP -> "(history, deep)"
}
