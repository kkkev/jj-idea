package `in`.kkkev.jjidea.actions.file

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.JujutsuDataKeys
import `in`.kkkev.jjidea.actions.JujutsuDataKeys.DiffContentInfo
import `in`.kkkev.jjidea.actions.editor
import `in`.kkkev.jjidea.actions.file
import `in`.kkkev.jjidea.actions.git.ClassifiedRemote
import `in`.kkkev.jjidea.actions.git.RemoteUrlBuilder
import `in`.kkkev.jjidea.actions.git.applyRemoteVisibility
import `in`.kkkev.jjidea.actions.repoForFile
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.vcs.possibleLogEntryFor
import java.io.File

/**
 * Action group that opens the current editor file at its latest pushed ancestor commit
 * in GitHub or GitLab. Registered in [RevealGroup] ("Open In" submenu) and [Diff.EditorPopupMenu].
 *
 * - 0 recognized remotes → hidden
 * - 1 recognized remote → transparent (child shown inline as "Open in GitHub 'origin'" with icon)
 * - 2+ recognized remotes → "Open on Remote" submenu
 *
 * Remote list is fetched lazily from [JujutsuRepository.gitRemotes] (cached per-repo for the session).
 * The commit hash lookup is deferred to [actionPerformed].
 *
 * ## Diff editor support
 *
 * When shown in a diff viewer popup ([Diff.EditorPopupMenu]), the action also checks
 * [DiffDataKeys.CURRENT_CONTENT] for [JujutsuDataKeys.DIFF_CONTENT_INFO] user data.
 * Diff actions annotate historical (non-local) content sides with this data so the
 * action can resolve repo, file path, and commit without a backing [VirtualFile].
 */
class OpenInRemoteFromEditorGroup : DefaultActionGroup() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val repo = e?.repoForFileOrDiff ?: return emptyArray()
        return RemoteUrlBuilder.classifiedRemotes(repo.gitRemotes).map { remote ->
            editorRemoteAction(remote)
        }.toTypedArray()
    }

    override fun update(e: AnActionEvent) {
        val repo = e.repoForFileOrDiff ?: run {
            e.presentation.isVisible = false
            return
        }
        applyRemoteVisibility(e, RemoteUrlBuilder.classifiedRemotes(repo.gitRemotes).size)
    }
}

/** Reads [JujutsuDataKeys.DIFF_CONTENT_INFO] from [DiffDataKeys.CURRENT_CONTENT] if present. */
private val AnActionEvent.diffContentInfo: DiffContentInfo?
    get() = getData(DiffDataKeys.CURRENT_CONTENT)?.getUserData(JujutsuDataKeys.DIFF_CONTENT_INFO)

/** Resolves the [JujutsuRepository] from the event, trying real-file context first, then diff content. */
private val AnActionEvent.repoForFileOrDiff: JujutsuRepository?
    get() = repoForFile ?: diffContentInfo?.repo

private val Editor.lineRange: IntRange
    get() {
        val doc = document
        return if (selectionModel.hasSelection()) {
            val startLine = doc.getLineNumber(selectionModel.selectionStart) + 1
            val endLine = doc.getLineNumber(maxOf(0, selectionModel.selectionEnd - 1)) + 1
            startLine..endLine
        } else {
            val line = caretModel.logicalPosition.line + 1
            line..line
        }
    }

private fun editorRemoteAction(remote: ClassifiedRemote) = object : DumbAwareAction(
    JujutsuBundle.message("log.action.open.in.remote.named", remote.kind.label, remote.name),
    JujutsuBundle.message("action.open.file.in.remote.from.editor.description"),
    remote.kind.icon
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val localLineRange = e.editor?.lineRange
        val localText = e.editor?.document?.text
        // Try real-file context first (regular editor or local side of a diff)
        val localFile = e.file
        val localRepo = e.repoForFile
        if (localFile != null && localRepo != null) {
            val logEntry = project.possibleLogEntryFor(localFile)
            val pinnedCommitId = logEntry?.takeUnless { it.isWorkingCopy }?.commitId
            openInRemote(
                project,
                localRepo,
                remote,
                localRepo.getRelativePath(localFile),
                pinnedCommitId,
                localLineRange,
                localText
            )
            return
        }
        // Fall back to diff content info (historical side of a diff viewer)
        val info = e.diffContentInfo ?: return
        openInRemote(
            project,
            info.repo,
            remote,
            info.repo.getRelativePath(info.filePath),
            info.commitId,
            localLineRange,
            localText
        )
    }
}

private fun openInRemote(
    project: Project,
    repo: JujutsuRepository,
    remote: ClassifiedRemote,
    relativePath: String,
    pinnedCommitId: CommitId?,
    localLineRange: IntRange?,
    localText: String?
) {
    runInBackground {
        val commitHash: String
        val remoteLineRange: IntRange?
        if (pinnedCommitId != null) {
            commitHash = pinnedCommitId.full
            remoteLineRange = localLineRange
        } else {
            val resolved = repo.commandExecutor.latestPushedAncestorCommitId(remote.name)
            if (resolved == null) {
                JujutsuNotifications.notify(
                    project,
                    JujutsuBundle.message("action.open.file.in.remote.no.pushed.title"),
                    JujutsuBundle.message("action.open.file.in.remote.no.pushed", remote.name),
                    NotificationType.INFORMATION
                )
                return@runInBackground
            }
            commitHash = resolved
            remoteLineRange = mapToRemoteLineRange(repo, relativePath, commitHash, localText, localLineRange)
        }
        BrowserUtil.browse(
            RemoteUrlBuilder.fileUrl(remote.base, remote.kind, commitHash, relativePath, remoteLineRange)
        )
    }
}

private fun mapToRemoteLineRange(
    repo: JujutsuRepository,
    relativePath: String,
    commitHash: String,
    localText: String?,
    localLineRange: IntRange?
): IntRange? {
    if (localText == null || localLineRange == null) return null
    val filePath = VcsUtil.getFilePath(File("${repo.directory.path}/$relativePath"), false)
    val remoteText = repo.commandExecutor.show(filePath, CommitId(commitHash))
        .takeIf { it.isSuccess }?.stdout ?: return null
    val fragments = ComparisonManager.getInstance()
        .compareLines(remoteText, localText, ComparisonPolicy.DEFAULT, EmptyProgressIndicator())
    return translateRange(fragments, localLineRange)
}

internal fun translateRange(fragments: List<LineFragment>, localRange: IntRange): IntRange {
    val start = mapLine(fragments, localRange.first - 1) + 1
    val end = mapLine(fragments, localRange.last - 1) + 1
    return start..maxOf(start, end)
}

private fun mapLine(fragments: List<LineFragment>, localLine: Int): Int {
    var delta = 0
    for (fragment in fragments) {
        if (localLine < fragment.startLine2) return (localLine - delta).coerceAtLeast(0)
        if (localLine < fragment.endLine2) return fragment.startLine1
        delta += (fragment.endLine2 - fragment.startLine2) - (fragment.endLine1 - fragment.startLine1)
    }
    return (localLine - delta).coerceAtLeast(0)
}
