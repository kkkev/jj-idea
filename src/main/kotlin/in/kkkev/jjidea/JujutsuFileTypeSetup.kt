package `in`.kkkev.jjidea

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.JujutsuVcs.Companion.DOT_JJ
import java.util.concurrent.atomic.AtomicBoolean

class JujutsuFileTypeSetup : ProjectActivity {
    companion object {
        private val done = AtomicBoolean(false)
    }

    override suspend fun execute(project: Project) {
        if (!done.compareAndSet(false, true)) return
        val ftm = FileTypeManager.getInstance()
        if (!ftm.isFileIgnored(DOT_JJ)) {
            runLater {
                WriteAction.run<Nothing> {
                    ftm.ignoredFilesList = "${ftm.ignoredFilesList};$DOT_JJ"
                }
            }
        }
    }
}
