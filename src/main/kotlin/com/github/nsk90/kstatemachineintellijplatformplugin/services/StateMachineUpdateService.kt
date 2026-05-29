package com.github.nsk90.kstatemachineintellijplatformplugin.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

private const val DEBOUNCE_MS = 400

/**
 * Emits a [VirtualFile] every time a Kotlin file in the project is edited,
 * debounced via a [MergingUpdateQueue] so a burst of keystrokes triggers a
 * single refresh.
 */
@Service(Service.Level.PROJECT)
class StateMachineUpdateService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {

    private val queue = MergingUpdateQueue(
        /* name = */ "KStateMachineUpdate",
        /* mergingTimeSpan = */ DEBOUNCE_MS,
        /* isActive = */ true,
        /* modalityStateComponent = */ null,
        /* parent = */ this,
    )

    private val _updates = MutableSharedFlow<VirtualFile>(extraBufferCapacity = 1)
    val updates = _updates.asSharedFlow()

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (file.extension !in KOTLIN_EXTENSIONS) return
                    if (!ProjectFileIndex.getInstance(project).isInContent(file)) return
                    queue.queue(object : Update("file:${file.url}") {
                        override fun run() {
                            coroutineScope.launch { _updates.emit(file) }
                        }
                    })
                }
            },
            this,
        )
    }

    override fun dispose() = Unit
}

private val KOTLIN_EXTENSIONS = setOf("kt", "kts")
