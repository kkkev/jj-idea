package `in`.kkkev.jjidea

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.JujutsuVcsBase.Companion.DOT_JJ
import java.util.concurrent.atomic.AtomicBoolean

class JujutsuFileTypeSetup : ProjectActivity {
    companion object {
        private val done = AtomicBoolean(false)
    }

    override suspend fun execute(project: Project) {
        // The ignored-files list is an IDE-wide setting, so only change it once a Jujutsu
        // repository is actually in use.
        project.stateModel.initialisedRepositories.connect(project) { roots ->
            if (roots.isNotEmpty()) ignoreDotJj()
        }
    }

    private fun ignoreDotJj() {
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
