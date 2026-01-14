package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile

val AnActionEvent.file: VirtualFile? get() = this.getData(CommonDataKeys.VIRTUAL_FILE)
val AnActionEvent.editor: Editor? get() = this.getData(CommonDataKeys.EDITOR)
