package `in`.kkkev.jjidea.actions.git

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.repoForFile
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.initialisedJujutsuRepositories
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * Fetch from a Git remote. Loads remotes off EDT, then:
 * - When there is only one repo with at most one remote, fetches immediately (no dialog).
 * - Otherwise opens [GitFetchDialog] so the user can choose repository and remote.
 *
 * Registered in plugin.xml and added to the VCS menu and log toolbar.
 */
class GitFetchAction : DumbAwareAction(
    JujutsuBundle.message("action.git.fetch"),
    JujutsuBundle.message("action.git.fetch.description"),
    AllIcons.Vcs.Fetch
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project.isJujutsu
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repos = project.initialisedJujutsuRepositories
        if (repos.isEmpty()) return
        val initialRepo = e.repoForFile ?: repos.first()

        runInBackground {
            val allData = GitFetchDialog.loadAllDialogData(repos)
            val totalRemotes = allData.values.sumOf { it.remotes.size }

            if (totalRemotes == 0) {
                runLater { noRemoteNotification(project) }
                return@runInBackground
            }

            if (repos.size == 1 && allData.values.first().remotes.size <= 1) {
                performFetch(GitFetchDialog.GitFetchSpec(repos.toList(), remote = null, allRemotes = false), project)
                return@runInBackground
            }

            runLater {
                val dialog = GitFetchDialog(project, allData, initialRepo)
                if (!dialog.showAndGet()) return@runLater
                val spec = dialog.result ?: return@runLater
                performFetch(spec, project)
            }
        }
    }
}
