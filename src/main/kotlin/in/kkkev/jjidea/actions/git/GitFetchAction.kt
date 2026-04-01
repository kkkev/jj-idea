package `in`.kkkev.jjidea.actions.git

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.vcs.isJujutsu
import `in`.kkkev.jjidea.vcs.jujutsuRepositories

/**
 * Fetch from Git remotes for all initialized JJ roots.
 * Registered in plugin.xml and added to the VCS menu and log toolbar.
 */
class GitFetchAction : DumbAwareAction(
    JujutsuBundle.message("action.git.fetch"),
    JujutsuBundle.message("action.git.fetch.description"),
    AllIcons.Vcs.Fetch
) {
    private val log = Logger.getInstance(javaClass)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.isJujutsu == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repos = project.jujutsuRepositories.filter { it.isInitialised }
        if (repos.isEmpty()) return

        repos.forEach { repo ->
            repo.commandExecutor
                .createCommand { gitFetch() }
                .onSuccess {
                    repo.invalidate(vfsChanged = true)
                    log.info("Fetched for ${repo.displayName}")
                }
                .onFailure { tellUser(project, "action.git.fetch.error") }
                .executeWithProgress(project, JujutsuBundle.message("progress.git.fetch"))
        }
    }
}
