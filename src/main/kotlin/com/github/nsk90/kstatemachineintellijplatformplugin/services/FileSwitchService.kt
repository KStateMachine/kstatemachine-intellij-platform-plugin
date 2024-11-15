package com.github.nsk90.kstatemachineintellijplatformplugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class FileSwitchService(project: Project, private val coroutineScope: CoroutineScope) {
    private val _fileSwitchedFlow = MutableSharedFlow<VirtualFile>()
    val fileSwitchedFlow = _fileSwitchedFlow.asSharedFlow()

    init {
        project.messageBus.connect()
            .subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    event.newFile?.let {
                        coroutineScope.launch { _fileSwitchedFlow.emit(it) }
                    }
                }
            })
    }
}