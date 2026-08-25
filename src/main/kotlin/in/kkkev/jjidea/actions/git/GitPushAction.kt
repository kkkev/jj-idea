package `in`.kkkev.jjidea.actions.git

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.logEntries
import `in`.kkkev.jjidea.actions.repoForFile
import `in`.kkkev.jjidea.actions.uniqueRepo
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.initialisedJujutsuRepositories
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * Push to a Git remote. Loads remotes/bookmarks off EDT, then opens a dialog to configure options.
 * Registered in plugin.xml and added to the VCS menu and log toolbar.
 *
 * When multiple repositories exist, the dialog includes a repository selector pre-populated from
 * the file context (if any). The push targets only the repo selected in the dialog.
 *
 * Non-fast-forward pushes (backward or sideways bookmark moves) are detected via a dry-run and
 * require explicit confirmation before proceeding. See [checkAndPush].
 */
class GitPushAction : DumbAwareAction(
    JujutsuBundle.message("action.git.push"),
    JujutsuBundle.message("action.git.push.description"),
    AllIcons.Vcs.Push
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

        // Read here, on EDT, before hopping to a background thread — same reason e.repoForFile
        // is read above rather than inside runInBackground.
        val logEntries = e.logEntries
        // A single-repo selection (from the log toolbar) offers "create bookmark(s) for
        // change(s)" for those specific commits (jj-idea-ikof); a cross-repo selection is
        // ambiguous and leaves this null, which hides just that one scope in the dialog below —
        // scopes 1-3 remain usable via the dialog's own repo picker. No selection at all (e.g.
        // invoked from the plain VCS menu) falls back to changeTargetsFor's own @/@- default,
        // scoped to initialRepo.
        val changeRepo = if (logEntries.isEmpty()) initialRepo else logEntries.uniqueRepo
        val changeTargets = changeRepo?.let { changeTargetsFor(it, logEntries) }.orEmpty()

        runInBackground {
            val allData = GitPushDialog.loadAllDialogData(repos)
            if (allData.values.all { it.remotes.isEmpty() }) {
                runLater { noRemoteNotification(project) }
                return@runInBackground
            }

            val defaultScope = GitPushDialog.parsePushScope(JujutsuSettings.getInstance(project).state.defaultPushScope)

            runLater {
                val dialog = GitPushDialog(
                    project,
                    allData,
                    initialRepo,
                    changeTargets = changeTargets,
                    changeTargetsRepo = changeRepo,
                    defaultScope = defaultScope
                )
                if (!dialog.showAndGet()) return@runLater

                val spec = dialog.result ?: return@runLater

                checkAndPush(spec, project)
            }
        }
    }
}
