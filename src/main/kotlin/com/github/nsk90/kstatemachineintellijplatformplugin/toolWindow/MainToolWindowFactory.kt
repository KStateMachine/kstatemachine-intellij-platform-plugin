package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.idea.completion.argList
import org.jetbrains.kotlin.psi.KtCallExpression
import javax.swing.JTextArea

private const val CREATE_FUNCTIONS_PREFIX = "createStateMachine"

private val createStateMachineFunctions = listOf(
    "createStateMachine",
    "createStateMachineBlocking",
    "createStdLibStateMachine",
)

private val stateFactoryFunctions = listOf(
    "state",
    "dataState",
    "initialState",
    "initialDataState",
    "finalDataState",
    "initialFinalDataState",
    "choiceState",
    "initialChoiceState",
    "initialChoiceState",
    "choiceDataState",
    "initialChoiceDataState",
    "historyState",
)

private val addStateFunctions = listOf(
    "addState",
    "addInitialState",
    "addFinalState",
)

private val transitionFunctions = listOf(
    "transition",
    "transitionOn",
    "transitionConditionally",
    "dataTransition",
    "dataTransitionOn",
)

private val stateFunctions = stateFactoryFunctions + addStateFunctions

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
            override fun run(indicator: ProgressIndicator) {
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
                // build psi tree for dsl statemachine structure
                findMethodCallsInElement(psiFile, createStateMachineFunctions).forEach {
                    logMessage("Found method call: ${it.calleeExpression?.text}")
                    // should go as deep as possible, and protect from duplicates
                    findMethodCallsInElement(it, stateFunctions).forEach {
                        logMessage("Found method call: ${it.calleeExpression?.text}, ${printArgumentValueByNameOrIndex(it, "name", 0)}")
                    }
                    findMethodCallsInElement(it, transitionFunctions).forEach {
                        logMessage("Found method call: ${it.calleeExpression?.text}, ${printArgumentValueByNameOrIndex(it, "name", 0)}")
                    }
                }
            }
        }
    }

    private fun printArgumentValueByNameOrIndex(callExpression: KtCallExpression, argumentName: String, index: Int): String {
        // Search by name
        val argument = callExpression.valueArguments.find {
            it.getArgumentName()?.asName?.asString() == argumentName
        }
        if (argument != null) {
             return "$argumentName: ${argument.getArgumentExpression()?.text}"
        }
        // Search by positional index
        val arguments = callExpression.valueArguments
        if (index in arguments.indices) {
            val argumentValue = arguments[index].getArgumentExpression()?.text ?: "null"
            return argumentValue
        } else {
            return "No argument found at index: $index"
        }
    }

    private fun findMethodCallsInElement(element: PsiElement, names: List<String>): List<KtCallExpression> {
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
