package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.github.nsk90.kstatemachineintellijplatformplugin.model.State
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateKind
import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.model.Transition
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import org.jetbrains.kotlin.psi.KtCallExpression
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class StateMachineTreePanel(private val project: Project) {
    private val rootNode = DefaultMutableTreeNode("State machines")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = StateMachineCellRenderer()
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
            val pointer = node.pointer() ?: return@addTreeSelectionListener
            navigateToPointer(pointer)
        }
    }

    fun setMachines(machines: List<StateMachine>) {
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
    }

    private fun buildNode(state: State): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(state)
        state.states.forEach { node.add(buildNode(it)) }
        state.transitions.forEach { node.add(DefaultMutableTreeNode(it)) }
        return node
    }

    private fun expandAll() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    private fun navigateToPointer(pointer: SmartPsiElementPointer<KtCallExpression>) {
        val element = pointer.element ?: return
        val vf = pointer.virtualFile ?: return
        OpenFileDescriptor(project, vf, element.textRange.startOffset).navigate(true)
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

private fun DefaultMutableTreeNode.pointer(): SmartPsiElementPointer<KtCallExpression>? =
    when (val data = userObject) {
        is State -> data.pointer
        is Transition -> data.pointer
        else -> null
    }

private class StateMachineCellRenderer : ColoredTreeCellRenderer() {
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
                icon = AllIcons.Nodes.Class
                append("StateMachine ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(displayName(data.name))
                val counts = data.subtreeCounts()
                append("  (${counts.first} states, ${counts.second} transitions)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is State -> {
                icon = data.kind.icon()
                append(displayName(data.name))
                val tag = data.kind.label()
                if (tag != null) append("  $tag", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (data.transitions.isNotEmpty()) {
                    append("  (${data.transitions.size} transition${if (data.transitions.size == 1) "" else "s"})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            is Transition -> {
                icon = AllIcons.Actions.Forward
                append(data.transitionLabel())
                val suffix = buildString {
                    if (data.eventType != null && !data.transitionLabel().contains(data.eventType)) {
                        append("  <${data.eventType}>")
                    }
                    if (!data.targetStateName.isNullOrBlank()) {
                        append("  → ${data.targetStateName}")
                    }
                }
                if (suffix.isNotEmpty()) {
                    append(suffix, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            else -> append(node.toString())
        }
    }
}

private fun displayName(rawName: String): String =
    if (rawName.isBlank() || rawName == "null" || rawName == "<unnamed>") "State (unnamed)" else rawName

private fun Transition.transitionLabel(): String = when {
    name.isNotBlank() && name != "null" && name != "<unnamed>" -> name
    eventType != null -> "on $eventType"
    else -> "(unnamed transition)"
}

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
    StateKind.INITIAL, StateKind.INITIAL_DATA -> AllIcons.Actions.Execute
    StateKind.FINAL, StateKind.FINAL_DATA -> AllIcons.Actions.Suspend
    StateKind.INITIAL_FINAL, StateKind.INITIAL_FINAL_DATA -> AllIcons.Actions.Suspend
    StateKind.CHOICE, StateKind.CHOICE_DATA -> AllIcons.Vcs.Branch
    StateKind.INITIAL_CHOICE, StateKind.INITIAL_CHOICE_DATA -> AllIcons.Vcs.Branch
    StateKind.HISTORY -> AllIcons.Vcs.History
    StateKind.STATE, StateKind.DATA -> AllIcons.Nodes.ModelClass
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
    StateKind.CHOICE -> "(choice)"
    StateKind.INITIAL_CHOICE -> "(initial choice)"
    StateKind.CHOICE_DATA -> "(choice data)"
    StateKind.INITIAL_CHOICE_DATA -> "(initial choice data)"
    StateKind.HISTORY -> "(history)"
}
