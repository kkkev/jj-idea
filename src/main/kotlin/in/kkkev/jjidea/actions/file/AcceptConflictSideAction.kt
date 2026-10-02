package `in`.kkkev.jjidea.actions.file

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.changes
import `in`.kkkev.jjidea.actions.logEntry
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.conflict.sideDisplayLabels
import `in`.kkkev.jjidea.ui.editor.acceptSideCommand
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.merge.JujutsuMergeProvider
import `in`.kkkev.jjidea.vcs.possibleJujutsuVcs

/**
 * Bulk accept-a-side for an explicit multi-selection of conflicted files (GitHub #66): lets a
 * user resolve several files at once by side, in one gesture, instead of stepping through them
 * one at a time via the merge-tool queue ([in.kkkev.jjidea.actions.change.resolveConflicts]).
 *
 * Deliberately **not** labelled "Accept Yours"/"Accept Theirs": toddjonker's GitHub #112 feedback
 * (see `docs/design/jj-idea-n6fz-native-conflict-ux.md`'s NOTES) already called that terminology
 * out as meaningless even in git, let alone jj. Menu text for *both* this action and its sibling
 * ([AcceptConflictCurrentSideAction]/[AcceptConflictLastSideAction]) is computed together by
 * [sideDisplayLabels] applied across every selected file: jj's own label when every file agrees on
 * it (the common case for a single file, or several files conflicted by the same rebase/merge,
 * where the label is naturally identical); its rarer alternate label when the primary label
 * collides with the other side's; otherwise the generic "Side #1"/"Side #2" for **both** actions
 * together - deliberately never a weaker signal like a shared role *word* alone (which could
 * coerce two genuinely unrelated conflicts that just happen to share jj's fixed vocabulary into a
 * single, misleading label), and deliberately never one action resolving to a specific label while
 * its sibling falls back to generic wording (see [sideDisplayLabels]'s doc for a concretely
 * reported case that motivated this). Never "Yours"/"Theirs" at any tier.
 *
 * Deliberately scoped to the explicit selection only (unlike
 * [ResolveSelectedConflictsAction.conflictedFilesFromContext], which falls back to inherited
 * working-copy conflicts or the single focused file when there's no tree selection) - this is a
 * bulk "act on exactly what I picked" gesture, not a catch-all resolve entry point, so an empty
 * selection just hides the action rather than guessing a broader target.
 *
 * Working-copy only, matching [in.kkkev.jjidea.actions.change.resolveSelectedAvailability]'s
 * NEEDS_EDIT gating: resolving writes to disk, which only makes sense for `@`.
 */
sealed class AcceptConflictSideAction(private val resolution: MergeSession.Resolution) : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        acceptConflictSide(project, selectedConflictedFiles(e), resolution)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val logEntry = e.logEntry
        val files = selectedConflictedFiles(e)
        val available = project != null &&
            project.possibleJujutsuVcs != null &&
            (logEntry == null || logEntry.isWorkingCopy) &&
            files.isNotEmpty()
        e.presentation.isEnabledAndVisible = available
        if (available) {
            e.presentation.text = JujutsuBundle.message("notification.conflict.accept", labelFor(project!!, files))
        }
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * jj's own label for the side this action accepts, across every selected file - one lookup
     * per file, same as [in.kkkev.jjidea.ui.editor.conflictBannerModel] does for the editor
     * banner. Computes *both* sides' labels via [sideDisplayLabels] (each action instance
     * recomputes both, redundant but cheap, since the two actions have no shared state) and picks
     * its own.
     */
    private fun labelFor(project: Project, files: List<VirtualFile>): String {
        val conflicts = files.map { file ->
            try {
                project.possibleJujutsuVcs?.mergeProvider?.loadConflict(file)
            } catch (_: VcsException) {
                null
            }
        }
        val (currentLabel, lastLabel) = sideDisplayLabels(
            currentTitles = conflicts.map { it?.currentTitle },
            currentAlternateTitles = conflicts.map { it?.currentAlternateTitle },
            lastTitles = conflicts.map { it?.lastTitle },
            lastAlternateTitles = conflicts.map { it?.lastAlternateTitle },
            currentFallback = JujutsuBundle.message("merge.column.side1"),
            lastFallback = JujutsuBundle.message("merge.column.side2")
        )
        return if (resolution == MergeSession.Resolution.AcceptedYours) currentLabel else lastLabel
    }

    private fun selectedConflictedFiles(e: AnActionEvent): List<VirtualFile> =
        scopeToConflicted(e.changes).orEmpty().mapNotNull { it.filePath.virtualFile }
}

/** Accepts the side jj extracted into `CURRENT` ([in.kkkev.jjidea.jj.conflict.ExtractedConflict.currentTitle]). */
class AcceptConflictCurrentSideAction : AcceptConflictSideAction(MergeSession.Resolution.AcceptedYours)

/** Accepts the side jj extracted into `LAST` ([in.kkkev.jjidea.jj.conflict.ExtractedConflict.lastTitle]). */
class AcceptConflictLastSideAction : AcceptConflictSideAction(MergeSession.Resolution.AcceptedTheirs)

/**
 * Groups [files] by (repo, [JujutsuMergeProvider.toolFor]) and runs one undo-tracked
 * `jj resolve --tool` per group via [in.kkkev.jjidea.ui.editor.acceptSideCommand] (jj-idea-n6fz.2):
 * the common case - every file from one rebase/merge in one repo - is a single jj op and a single
 * Undo balloon that reverts the whole selection. Mixed orientations (GitHub #112) or repos
 * split into a few groups, each with its own balloon reverting exactly its own op. Per-file tool
 * selection reuses the merge provider's orientation and `:ours`/`:theirs` fallback logic, so
 * modify/delete conflicts still delete rather than leaving empty content. Runs off the EDT since
 * the extraction reads files.
 */
internal fun acceptConflictSide(
    project: Project,
    files: List<VirtualFile>,
    resolution: MergeSession.Resolution,
    mergeProviderFor: (Project) -> JujutsuMergeProvider? = { it.possibleJujutsuVcs?.mergeProvider },
    runInBackground: (() -> Unit) -> Unit = { ApplicationManager.getApplication().executeOnPooledThread(it) },
    commandFor: (JujutsuRepository, List<VirtualFile>, String) -> CommandExecutor.Command.WithRepo =
        { repo, group, tool -> acceptSideCommand(project, repo, group, tool) }
) {
    if (files.isEmpty()) return
    val mergeProvider = mergeProviderFor(project) ?: return
    runInBackground {
        val noRepo = mutableListOf<VirtualFile>()
        val groups = linkedMapOf<Pair<JujutsuRepository, String>, MutableList<VirtualFile>>()
        for (file in files) {
            val repo = mergeProvider.repositoryFor(file)
            if (repo == null) {
                noRepo += file
                continue
            }
            groups.getOrPut(repo to mergeProvider.toolFor(file, resolution)) { mutableListOf() } += file
        }
        groups.forEach { (key, group) -> commandFor(key.first, group, key.second).executeAsync() }
        if (noRepo.isNotEmpty()) {
            JujutsuNotifications.notify(
                project,
                JujutsuBundle.message("notification.resolve.failed.title"),
                JujutsuBundle.message(
                    "notification.resolve.failed.message",
                    noRepo.joinToString("\n") { "${it.name}: ${JujutsuBundle.message("merge.resolve.noRepo")}" }
                ),
                NotificationType.ERROR
            )
        }
    }
}
