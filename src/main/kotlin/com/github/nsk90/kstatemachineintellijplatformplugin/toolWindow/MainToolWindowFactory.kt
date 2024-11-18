package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.github.nsk90.kstatemachineintellijplatformplugin.psi.Output
import com.github.nsk90.kstatemachineintellijplatformplugin.psi.PsiElementsParser
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.github.nsk90.kstatemachineintellijplatformplugin.services.FileSwitchService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.swing.JTextArea

private const val BACKGROUND_TASK_NAME = "Looking for state machines"
private const val PROCESSING = "KStateMachine Visual processing..."

class MainToolWindowFactory : ToolWindowFactory {
    private lateinit var project: Project
    private lateinit var logTextArea: JTextArea
    private lateinit var toolWindowWorkingScope: CoroutineScope

    override fun init(toolWindow: ToolWindow) {
        toolWindowWorkingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fileSwitchService = toolWindow.project.service<FileSwitchService>()
        toolWindowWorkingScope.launch {
            fileSwitchService.fileSwitchedFlow.collect {
                onFileSwitched(it)
            }
        }

        Disposer.register(toolWindow.contentManager) {
            toolWindowWorkingScope.cancel()
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        this.project = project
        //val myToolWindow = MyToolWindow(toolWindow, "test")
        //val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        //toolWindow.contentManager.addContent(content)

        createLogContent(toolWindow)
    }

    private fun createLogContent(toolWindow: ToolWindow) {
        logTextArea = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }

        // Wrap JTextArea in JScrollPane for scrollability
        val scrollPane = JBScrollPane(logTextArea)

        // Create content for the tool window
        val content = ContentFactory.getInstance().createContent(scrollPane, "Log Output", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun logMessage(message: String) {
        ApplicationManager.getApplication().invokeLater {
            logTextArea.append("$message\n")
            logTextArea.caretPosition = logTextArea.document.length // Scroll to the bottom
            thisLogger().warn(message)
        }
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

    private fun onFileSwitched(file: VirtualFile) {
        runTaskWithProgress(project) {
            runReadAction {
                try {
                    val psiFile =
                        PsiManager.getInstance(project).findFile(file) ?: error("Can't find file ${file.path}")
                    PsiElementsParser(Output { logMessage(it) }).parse(psiFile)
                } catch (e: Exception) {
                    logMessage("Error: $e, ${e.localizedMessage}")
                }
            }
        }
    }
//
//    fun isExpectedCreateStateMachine(callExpression: KtCallExpression, expectedFqName: String): Boolean {
//        // Resolve the function reference
//        val context = callExpression.analyze() // Analyze the file to get the binding context
//        val resolvedCall = callExpression.getResolvedCall(context)
//
//        // Get the fully qualified name of the resolved function
//        val fqName = resolvedCall?.resultingDescriptor?.fqNameOrNull()?.asString()
//
//        // Compare with the expected fully qualified name
//        return fqName == expectedFqName
//    }



//    private class MyToolWindow(private val toolWindow: ToolWindow, private val text: String) {
//
//        fun getContent() = JBPanel<JBPanel<*>>().apply {
//
//            add(
//                JBLabel(
//                    MyBundle.message(
//                        "stateMachineLabel",
//                        if (text.contains("createStateMachine")) "state machine Detected"
//                        else "state machine NOT detected"
//                    )
//                )
//            )
//
//            add(JButton(MyBundle.message("button")).apply {
//                addActionListener {
//                   // label.text = MyBundle.message("randomLabel", service.getRandomNumber())
//                }
//            })
//        }
//    }
}
