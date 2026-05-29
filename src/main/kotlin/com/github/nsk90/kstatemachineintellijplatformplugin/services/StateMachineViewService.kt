package com.github.nsk90.kstatemachineintellijplatformplugin.services

import com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow.StateMachineDiagramPanel
import com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow.StateMachineTreePanel
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import org.jetbrains.kotlin.psi.KtCallExpression

const val TOOL_WINDOW_ID = "KStateMachine"

/**
 * Cross-component bridge: keeps a reference to the live tree / diagram panels
 * so that other surfaces (gutter icons, actions) can activate the tool window
 * and drive selection without owning UI lifecycle.
 */
@Service(Service.Level.PROJECT)
class StateMachineViewService(private val project: Project) {

    @Volatile
    private var treePanelRef: StateMachineTreePanel? = null

    @Volatile
    private var diagramPanelRef: StateMachineDiagramPanel? = null

    fun bind(tree: StateMachineTreePanel, diagram: StateMachineDiagramPanel) {
        treePanelRef = tree
        diagramPanelRef = diagram
    }

    val diagramPanel: StateMachineDiagramPanel? get() = diagramPanelRef

    fun activateAndSelect(call: KtCallExpression) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        val offset = call.textRange.startOffset
        toolWindow.activate({
            treePanelRef?.selectNodeForOffset(offset)
        }, /* autoFocusContents = */ true, /* forced = */ true)
    }
}
