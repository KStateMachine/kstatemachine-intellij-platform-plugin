package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.github.nsk90.kstatemachineintellijplatformplugin.model.StateMachine
import com.github.nsk90.kstatemachineintellijplatformplugin.psi.Output
import com.github.nsk90.kstatemachineintellijplatformplugin.psi.PsiElementsParser
import com.github.nsk90.kstatemachineintellijplatformplugin.services.FileSwitchService
import com.github.nsk90.kstatemachineintellijplatformplugin.services.StateMachineUpdateService
import com.github.nsk90.kstatemachineintellijplatformplugin.services.StateMachineViewService
import com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow.actions.CopyPlantUmlAction
import com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow.actions.ExportDiagramAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.psi.KtFile

private const val BACKGROUND_TASK_NAME = "Looking for state machines"
private const val PROCESSING = "KStateMachine Visual processing..."

class MainToolWindowFactory : ToolWindowFactory {
    private lateinit var project: Project
    private lateinit var treePanel: StateMachineTreePanel
    private lateinit var diagramPanel: StateMachineDiagramPanel
    private lateinit var toolWindowWorkingScope: CoroutineScope

    @Volatile
    private var currentFile: VirtualFile? = null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        this.project = project
        treePanel = StateMachineTreePanel(project)
        diagramPanel = StateMachineDiagramPanel()
        project.service<StateMachineViewService>().bind(treePanel, diagramPanel)

        val tabs = JBTabbedPane().apply {
            addTab("Structure", treePanel.component)
            addTab("Diagram", diagramPanel.component)
        }

        val content = ContentFactory.getInstance().createContent(tabs, null, false)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(CopyPlantUmlAction(), ExportDiagramAction()))

        toolWindowWorkingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fileSwitchService = project.service<FileSwitchService>()
        val updateService = project.service<StateMachineUpdateService>()
        toolWindowWorkingScope.launch {
            fileSwitchService.fileSwitchedFlow.collect { onTabSwitched(it) }
        }
        toolWindowWorkingScope.launch {
            updateService.updates.collect { onDocumentEdited(it) }
        }

        registerCaretListener(toolWindow)

        Disposer.register(toolWindow.contentManager) {
            toolWindowWorkingScope.cancel()
        }

        // FileSwitchService only fires on tab CHANGES; when the tool window
        // first opens, the editor already has a file selected and no switch
        // event will fire. Parse the active file immediately so the tree
        // populates without requiring the user to click another file and back.
        FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let { active ->
            onInitialOpen(active)
        }
    }

    private fun registerCaretListener(toolWindow: ToolWindow) {
        EditorFactory.getInstance().eventMulticaster.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    val editor = event.editor
                    if (editor.project != project) return
                    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
                    if (file != currentFile) return
                    treePanel.selectNodeForOffset(editor.caretModel.offset)
                }
            },
            toolWindow.contentManager,
        )
    }

    private fun runTaskWithProgress(project: Project, block: () -> Unit) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, BACKGROUND_TASK_NAME) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = PROCESSING
                block()
            }
        })
    }

    // Tab switch (FileSwitchService): only commit to a new file if it actually
    // contains machines. Otherwise keep the previous view so the user can browse
    // helper files while keeping the state-machine structure on screen.
    private fun onTabSwitched(file: VirtualFile) {
        runTaskWithProgress(project) {
            val machines = parseFileOrNull(file) ?: return@runTaskWithProgress
            if (machines.isEmpty()) return@runTaskWithProgress
            currentFile = file
            applyToUi(machines)
        }
    }

    // Live document edit (StateMachineUpdateService): only ever applies to the
    // currently-displayed file, and always commits — even to an empty list,
    // because the user might have just deleted their createStateMachine call
    // and needs to see that reflected.
    private fun onDocumentEdited(file: VirtualFile) {
        if (file != currentFile) return
        runTaskWithProgress(project) {
            val machines = parseFileOrNull(file) ?: emptyList()
            applyToUi(machines)
        }
    }

    // First file the tool window sees when it opens. Same rule as a tab switch
    // for non-empty results, but we explicitly render an empty-state placeholder
    // when there's nothing to show so the user isn't staring at a blank panel.
    private fun onInitialOpen(file: VirtualFile) {
        runTaskWithProgress(project) {
            val machines = parseFileOrNull(file)
            if (machines.isNullOrEmpty()) {
                ApplicationManager.getApplication().invokeLater {
                    treePanel.clear()
                    diagramPanel.showPlaceholder("Open a Kotlin file with a state machine to start")
                }
                return@runTaskWithProgress
            }
            currentFile = file
            applyToUi(machines)
        }
    }

    /** Returns null only when [file] is not a Kotlin file or parsing throws. */
    private fun parseFileOrNull(file: VirtualFile): List<StateMachine>? = try {
        runReadActionBlocking {
            val psiFile = PsiManager.getInstance(project).findFile(file) as? KtFile
            psiFile?.let { PsiElementsParser(Output { thisLogger().info(it) }).parse(it) }
        }
    } catch (e: Exception) {
        thisLogger().warn("Failed to parse ${file.path}", e)
        null
    }

    private fun applyToUi(machines: List<StateMachine>) {
        ApplicationManager.getApplication().invokeLater {
            treePanel.setMachines(machines)
            diagramPanel.render(machines)
        }
    }
}
