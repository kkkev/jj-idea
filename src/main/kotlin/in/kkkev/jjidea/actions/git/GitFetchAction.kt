package `in`.kkkev.jjidea.actions.git

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.TooltipDescriptionProvider
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.FetchTimes
import `in`.kkkev.jjidea.util.relativeAgo
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.initialisedJujutsuRepositories
import `in`.kkkev.jjidea.vcs.isJujutsu
import java.time.Instant

/**
 * Fetch from a Git remote. Loads remotes off EDT, then:
 * - When there is only one repo with at most one remote, fetches immediately (no dialog).
 * - Otherwise opens [GitFetchDialog] so the user can choose repository and remote.
 *
 * Registered in plugin.xml and added to the VCS menu and log toolbar.
 */
class GitFetchAction :
    DumbAwareAction(
        JujutsuBundle.message("action.git.fetch"),
        JujutsuBundle.message("action.git.fetch.description"),
        AllIcons.Vcs.Fetch
    ),
    TooltipDescriptionProvider {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project.isJujutsu
        e.presentation.description = fetchDescription(e.project)
    }

    /** "Fetch from Git remote (last fetched: origin 3 min ago, upstream 3 min ago)"; just the base text if never fetched. */
    private fun fetchDescription(project: com.intellij.openapi.project.Project?): String {
        val base = JujutsuBundle.message("action.git.fetch.description")
        val repos = project?.initialisedJujutsuRepositories.orEmpty()
        val now = Instant.now()
        val times = repos.flatMap { repo ->
            FetchTimes.lastFetchedByRemote(repo.directory.path)
                .map { (remote, at) -> (if (repos.size > 1) "${repo.displayName}/$remote" else remote) to at }
        }
        if (times.isEmpty()) return base
        val list = times.joinToString(", ") { (label, at) -> "$label ${relativeAgo(at, now)}" }
        return "$base (${JujutsuBundle.message("action.git.fetch.last", list)})"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repos = project.initialisedJujutsuRepositories
        if (repos.isEmpty()) return

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
                val dialog = GitFetchDialog(project, allData)
                if (!dialog.showAndGet()) return@runLater
                val spec = dialog.result ?: return@runLater
                performFetch(spec, project)
            }
        }
    }
}
