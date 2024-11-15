package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.github.nsk90.kstatemachineintellijplatformplugin.MyBundle
import com.github.nsk90.kstatemachineintellijplatformplugin.services.FileSwitchService
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.psi.KtCallExpression
import javax.swing.JButton
import javax.swing.JTextArea

private const val CREATE_FUNCTIONS_PREFIX = "createStateMachine"

private val createStateMachineFunctions = listOf(
    "createStateMachine",
    "createStateMachineBlocking",
    "createStdLibStateMachine",
)

class MainToolWindowFactory : ToolWindowFactory {
    private lateinit var project: Project
    private lateinit var logTextArea: JTextArea
    private lateinit var toolWindowWorkingScope: CoroutineScope

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
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Task name") {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Processing something..."
                block()
            }
        })
    }

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

    private fun onFileSwitched(file: VirtualFile) {
        logMessage("Switched to file: ${file.path}")

        runTaskWithProgress(project) {
            runReadAction {
                val psiFile = PsiManager.getInstance(project).findFile(file) ?: error("Can't find file ${file.path}")

                findMethodCallsInFile(psiFile, createStateMachineFunctions).forEach {
                    logMessage("Found method call: ${it.calleeExpression?.text}")
                }
            }
        }
    }

    private fun findMethodCallsInFile(element: PsiElement, names: List<String>): List<KtCallExpression> {
        return PsiTreeUtil.findChildrenOfType(element, KtCallExpression::class.java).mapNotNull {
            it.takeIf { names.contains(it.calleeExpression?.text) }
        }
    }

//    private fun findCreateStateMachineDeclarations(psiFile: PsiFile) {
//        logMessage("findCreateStateMachineDeclarations")
//        val result = PsiSearchHelper.getInstance(project).processElementsWithWord(
//            { psiElement, offsetInElement ->
//                if (psiElement is KtCallExpression) {
//                    val methodName = psiElement.calleeExpression?.text
//                    logMessage("methodName: $methodName")
////                    if (createStateMachineFunctions.contains(methodName)) {
////                        logMessage("Found state machine: $methodName")
////                    }
//                }
//                true
//            },
//            GlobalSearchScope.fileScope(psiFile),
//            CREATE_FUNCTIONS_PREFIX,
//            UsageSearchContext.IN_CODE,
//            false, // Case-sensitive search
//            true
//        )
//        logMessage("findCreateStateMachineDeclarations end $result")
//    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        this.project = project
        //val myToolWindow = MyToolWindow(toolWindow, "test")
        //val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        //toolWindow.contentManager.addContent(content)

        createLogContent(toolWindow)
    }

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
