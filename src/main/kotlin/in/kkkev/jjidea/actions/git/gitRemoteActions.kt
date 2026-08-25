package `in`.kkkev.jjidea.actions.git

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.BookmarkName
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

private val log = Logger.getInstance("in.kkkev.jjidea.actions.git.gitRemoteActions")

internal fun noRemoteNotification(project: Project) =
    JujutsuNotifications.notify(
        project,
        JujutsuBundle.message("action.git.no.remote.title"),
        JujutsuBundle.message("action.git.no.remote.message"),
        NotificationType.WARNING
    )

internal fun performFetch(spec: GitFetchDialog.GitFetchSpec, project: Project) {
    spec.repos.forEach { repo ->
        val remoteLabel = when {
            spec.allRemotes -> JujutsuBundle.message("action.git.fetch.remote.all")
            spec.remote != null -> spec.remote.name
            else -> repo.gitRemotes.firstOrNull()?.name ?: repo.displayName
        }
        repo.commandExecutor
            .createCommand { gitFetch(spec.remote, spec.allRemotes) }
            .onSuccess {
                repo.invalidate(vfsChanged = true)
                log.info("Fetched for ${repo.displayName}")
            }
            .onFailure { tellUser(project, "action.git.fetch.error") }
            .executeWithProgress(project, JujutsuBundle.message("progress.git.fetch", remoteLabel))
    }
}

/**
 * Factory action for fetching from Git remote for a specific repository.
 * Used in log context menu where a single repo is known.
 * Skips the dialog when there is only one remote (nothing to choose).
 */
fun gitFetchAction(project: Project, repo: JujutsuRepository?) =
    nullAndDumbAwareAction(repo, "log.action.git.fetch", AllIcons.Vcs.Fetch) {
        runInBackground {
            val data = GitFetchDialog.loadDialogData(target)
            if (data.remotes.isEmpty()) {
                runLater { noRemoteNotification(project) }
                return@runInBackground
            }
            if (data.remotes.size == 1) {
                performFetch(GitFetchDialog.GitFetchSpec(listOf(target), remote = null, allRemotes = false), project)
                return@runInBackground
            }
            runLater {
                val dialog = GitFetchDialog(project, mapOf(target to data))
                if (!dialog.showAndGet()) return@runLater
                performFetch(dialog.result ?: return@runLater, project)
            }
        }
    }

/**
 * Factory action for pushing to Git remote for a specific repository.
 * Loads remotes/bookmarks off EDT, then opens the push dialog.
 * @param entries The log selection this Push was invoked from (context menu only — the toolbar/
 *   VCS-menu button is a different action, [GitPushAction]). Empty selection or the working-copy
 *   row alone behave as before this parameter existed: bookmarks aren't revision-scoped, and the
 *   "create bookmark for change" scope offers `@`/`@-`. A single non-`@` selection scopes
 *   bookmarks to that revision's ancestry and offers just that change. A multi-selection (always
 *   single-repo here — [repo] is `null` and this whole action is disabled otherwise, see
 *   [in.kkkev.jjidea.ui.log.JujutsuLogContextMenuActions]) offers pushing all selected changes at
 *   once via repeated `--change` flags (jj-idea-ikof).
 */
fun gitPushAction(project: Project, repo: JujutsuRepository?, entries: List<LogEntry> = emptyList()) =
    nullAndDumbAwareAction(repo, "log.action.git.push", AllIcons.Vcs.Push) {
        // -r only ever scopes the DEFAULT (tracking-bookmarks) push to a single commit's
        // ancestry, exactly as before this action started also accepting multi-selection: a
        // multi-selection leaves it null, same as no selection at all.
        val revision = entries.singleOrNull()?.id

        // Load remotes and bookmarks off EDT, then show dialog on EDT
        runInBackground {
            val data = GitPushDialog.loadDialogData(target, revision)
            if (data.remotes.isEmpty()) {
                runLater {
                    JujutsuNotifications.notify(
                        project,
                        JujutsuBundle.message("action.git.no.remote.title"),
                        JujutsuBundle.message("action.git.no.remote.message"),
                        NotificationType.WARNING
                    )
                }
                return@runInBackground
            }

            val changeTargets = changeTargetsFor(target, entries)
            val defaultScope = GitPushDialog.parsePushScope(JujutsuSettings.getInstance(project).state.defaultPushScope)

            runLater {
                val dialog = GitPushDialog(
                    project,
                    mapOf(target to data),
                    target,
                    changeTargets = changeTargets,
                    changeTargetsRepo = target,
                    defaultScope = defaultScope
                )
                if (!dialog.showAndGet()) return@runLater

                val spec = dialog.result ?: return@runLater

                checkAndPush(spec, project, revision)
            }
        }
    }

/**
 * The targets for the push dialog's "create bookmark(s) for change(s)" scope
 * (GitHub #65, jj-idea-fmzr/jj-idea-ikof): one entry per selected log commit. When nothing is
 * selected, falls back to a single-element list: `@`, or `@-` if `@` is empty — satisfying both
 * documented `jj git push --change` workflows (`jj commit; push --change @-` and
 * `describe; push --change @; new`) without the user having to think about it. `jj git push`
 * natively supports repeating `--change` for several revisions in one push (verified against jj
 * 0.44), so a multi-selection pushes all of it in one dialog confirmation.
 */
internal fun changeTargetsFor(repo: JujutsuRepository, entries: List<LogEntry>): List<Revision> =
    entries.map { it.id }.ifEmpty {
        listOf(if (repo.workingCopy.isEmpty) WorkingCopy.parent else WorkingCopy)
    }

/**
 * Runs a dry-run push to detect new remote bookmarks and non-fast-forward bookmark moves, then
 * shows a confirmation dialog if needed before performing the actual push. Called on EDT.
 *
 * - New/untracked bookmarks: jj refuses to create them on the remote without being tracked first
 *   (as an `Error` when a specific bookmark is targeted, or a `Warning` with the bookmark silently
 *   skipped for the default/tracking-bookmarks scope) → warn, then track and retry on confirmation.
 * - Tracked bookmarks moved backwards or sideways: jj reports direction in dry-run stderr → warn.
 * - Forward-moving or `--all` (which creates new remote bookmarks unconditionally): proceed silently.
 */
fun checkAndPush(spec: GitPushDialog.GitPushSpec, project: Project, revision: Revision? = null) {
    runInBackground {
        val dryRun = spec.repo.commandExecutor.gitPush(
            remote = spec.remote,
            bookmark = spec.bookmark,
            allBookmarks = spec.allBookmarks,
            changeRevisions = spec.changeRevisions,
            revision = revision,
            dryRun = true
        )

        val newBookmarks = parseRefusedNewBookmarks(dryRun.stderr)
        if (newBookmarks.isNotEmpty()) {
            runLater {
                if (confirmUntrackedPush(project, newBookmarks.map { it.localName })) {
                    trackAndPush(spec, project, revision, newBookmarks)
                }
            }
            return@runInBackground
        }

        if (!dryRun.isSuccess) {
            runLater { dryRun.tellUser(project, "action.git.push.error") }
            return@runInBackground
        }

        val forcePushBookmarks = parseForcePushBookmarks(dryRun.stderr)
        val deletedBookmarks = parseDeletedBookmarks(dryRun.stderr)

        runLater {
            if ((forcePushBookmarks.isEmpty() && deletedBookmarks.isEmpty()) ||
                confirmPush(project, forcePushBookmarks, deletedBookmarks)
            ) {
                performPush(spec, project, revision)
            }
        }
    }
}

/**
 * Tracks the new remote bookmarks in a single command (they all share the same remote, since
 * they came from one push's dry-run), then performs the push. Called off EDT.
 */
private fun trackAndPush(
    spec: GitPushDialog.GitPushSpec,
    project: Project,
    revision: Revision?,
    newBookmarks: List<BookmarkName>
) {
    runInBackground {
        val result = spec.repo.commandExecutor.bookmarkTrack(newBookmarks)
        if (!result.isSuccess) {
            runLater { result.tellUser(project, "action.git.push.error") }
            return@runInBackground
        }
        performPush(spec, project, revision)
    }
}

private fun performPush(spec: GitPushDialog.GitPushSpec, project: Project, revision: Revision?) {
    spec.repo.commandExecutor
        .createCommand {
            gitPush(
                spec.remote,
                spec.bookmark,
                spec.allBookmarks,
                changeRevisions = spec.changeRevisions,
                revision = revision
            )
        }
        .onSuccessResult {
            spec.repo.invalidate()
            log.info("Pushed for ${spec.repo.displayName}")
            JujutsuNotifications.notify(
                project,
                JujutsuBundle.message("action.git.push.success.title"),
                pushSuccessMessage(stdout, stderr),
                NotificationType.INFORMATION
            )
        }
        .onFailure { tellUser(project, "action.git.push.error") }
        .executeWithProgress(project, JujutsuBundle.message("progress.git.push"))
}

// jj writes "Nothing changed." (and similar no-op notices) to stderr even on a successful
// (exit 0) push, so a blank-stdout success must fall back to stderr before the generic default -
// otherwise a no-op push reports "Push complete" (jj-idea-idm0).
internal fun pushSuccessMessage(stdout: String, stderr: String): String =
    stdout.ifBlank { stderr }.ifBlank { JujutsuBundle.message("action.git.push.success.message.default") }

// jj outputs "Error: Refusing to create new remote bookmark X@Y" (specific bookmark, exit 1) or
// "Warning: Refusing to create new remote bookmark X@Y" (default scope, exit 0, bookmark silently
// skipped) when a bookmark doesn't yet exist on the remote and isn't tracked.
private val REFUSING_NEW_BOOKMARK_MARKER = Regex("""Refusing to create new remote bookmark (\S+)""")

/** Parses dry-run stderr for bookmarks jj refused to push because they aren't tracked yet. */
internal fun parseRefusedNewBookmarks(stderr: String): List<BookmarkName> =
    stderr.lines()
        .mapNotNull { REFUSING_NEW_BOOKMARK_MARKER.find(it)?.groupValues?.get(1) }
        .map { BookmarkName(it) }

// jj outputs "Move sideways bookmark X from Y to Z" or "Move backward bookmark X from Y to Z"
// for non-fast-forward pushes. Both require a force-push and must be confirmed.
private val FORCE_PUSH_MARKERS = listOf("Move sideways bookmark ", "Move backward bookmark ")

/** Parses dry-run stderr for non-fast-forward bookmark moves, returning the bookmark names. */
internal fun parseForcePushBookmarks(stderr: String): List<String> =
    stderr.lines()
        .mapNotNull { line -> FORCE_PUSH_MARKERS.firstOrNull { line.contains(it) }?.let { line to it } }
        .map { (line, marker) -> line.substringAfter(marker).substringBefore(" from ") }
        .filter { it.isNotEmpty() }

// jj outputs "Delete bookmark X from Y" for bookmarks pending local deletion being pushed.
private const val DELETE_MARKER = "Delete bookmark "

/** Parses dry-run stderr for pending bookmark deletions, returning the bookmark names. */
internal fun parseDeletedBookmarks(stderr: String): List<String> =
    stderr.lines()
        .mapNotNull { line ->
            line.takeIf { it.contains(DELETE_MARKER) }
                ?.substringAfter(DELETE_MARKER)
                ?.substringBefore(" from ")
                ?.takeIf { it.isNotEmpty() }
        }

private fun confirmUntrackedPush(project: Project, bookmarkNames: List<String>): Boolean {
    val list = bookmarkNames.joinToString("\n") { "  • $it" }
    return Messages.showYesNoDialog(
        project,
        JujutsuBundle.message("action.git.push.untracked.message", list),
        JujutsuBundle.message("action.git.push.sideways.title"),
        JujutsuBundle.message("action.git.push.sideways.push"),
        JujutsuBundle.message("action.git.push.sideways.cancel"),
        Messages.getWarningIcon()
    ) == Messages.YES
}

private fun confirmPush(project: Project, forcePushBookmarks: List<String>, deletedBookmarks: List<String>): Boolean {
    val parts = buildList {
        if (forcePushBookmarks.isNotEmpty()) {
            val list = forcePushBookmarks.joinToString("\n") { "  • $it" }
            add(JujutsuBundle.message("action.git.push.confirm.force.message", list))
        }
        if (deletedBookmarks.isNotEmpty()) {
            val list = deletedBookmarks.joinToString("\n") { "  • $it" }
            add(JujutsuBundle.message("action.git.push.confirm.delete.message", list))
        }
    }
    return Messages.showYesNoDialog(
        project,
        parts.joinToString("\n\n"),
        JujutsuBundle.message("action.git.push.sideways.title"),
        JujutsuBundle.message("action.git.push.sideways.push"),
        JujutsuBundle.message("action.git.push.sideways.cancel"),
        Messages.getWarningIcon()
    ) == Messages.YES
}
