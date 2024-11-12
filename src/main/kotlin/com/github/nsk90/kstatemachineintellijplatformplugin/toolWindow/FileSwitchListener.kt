package com.github.nsk90.kstatemachineintellijplatformplugin.toolWindow

import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class FileSwitchListener(project: Project) {

    init {
        val connection = project.messageBus.connect()

        // Register the FileEditorManagerListener
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                // Get the new file
                val newFile: VirtualFile? = event.newFile
                if (newFile != null) {
                    onFileSwitched(newFile, project)
                }
            }
        })
    }

    private fun onFileSwitched(file: VirtualFile, project: Project) {
        // Custom logic for when a file is switched
        println("User switched to file: ${file.name}")
        // You can add additional logic here, such as parsing or updating a tool window
    }
}