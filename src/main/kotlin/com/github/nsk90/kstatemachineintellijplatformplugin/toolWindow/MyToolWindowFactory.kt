package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.github.nsk90.kstatemachineintellijplatformplugin.MyBundle
import com.github.nsk90.kstatemachineintellijplatformplugin.services.MyProjectService
import com.intellij.openapi.components.services
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.JButton

class MyToolWindowFactory : ToolWindowFactory {

    init {
        thisLogger().warn("Don't forget to remove all non-needed sample code files with their corresponding registration entries in `plugin.xml`.")
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: error("Editor not available")
        val document = editor.document
        val text = document.text

        val myToolWindow = MyToolWindow(toolWindow, text)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), null, false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project) = true

    private class MyToolWindow(private val toolWindow: ToolWindow, private val text: String) {

        private val service = toolWindow.project.service<MyProjectService>()

        fun getContent() = JBPanel<JBPanel<*>>().apply {

            add(
                JBLabel(
                    MyBundle.message(
                        "stateMachineLabel",
                        if (text.contains("createStateMachine")) "state machine Detected"
                        else "state machine NOT detected"
                    )
                )
            )

            val label = JBLabel(MyBundle.message("randomLabel", "?"))
            add(JBLabel(MyBundle.message("outputLabel", parseCurrentFileContent(toolWindow.project))))
            add(label)
            add(JButton(MyBundle.message("shuffle")).apply {
                addActionListener {
                    label.text = MyBundle.message("randomLabel", service.getRandomNumber())
                }
            })
        }

        fun parseCurrentFileContent(project: Project): String {
            // Get the current editor and document
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return "No editor selected"
            val document = editor.document

            // Retrieve the VirtualFile from the document
            val virtualFile = FileDocumentManager.getInstance().getFile(document) ?: return "No file open"

            // Convert the VirtualFile to a PsiFile
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return "Cannot parse file"

            // Example: Retrieve all classes and methods
            val classes = PsiTreeUtil.findChildrenOfType(psiFile, PsiClass::class.java)
            val output = StringBuilder()

            for (psiClass in classes) {
                output.append("Class: ").append(psiClass.name).append("\n")

                // Find methods in each class
                val methods = psiClass.methods
                for (method in methods) {
                    output.append(" - Method: ").append(method.name).append("\n")
                }
            }

            return output.toString().ifEmpty { "Nothing to show" }
        }
    }
}
