package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.addPopup
import `in`.kkkev.jjidea.actions.bookmark.createBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.deleteBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.forgetBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.moveBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.moveBookmarkToChangeAction
import `in`.kkkev.jjidea.actions.bookmark.renameBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.toggleTrackBookmarkAction
import `in`.kkkev.jjidea.actions.change.abandonChangeAction
import `in`.kkkev.jjidea.actions.change.copyDescriptionAction
import `in`.kkkev.jjidea.actions.change.copyIdAction
import `in`.kkkev.jjidea.actions.change.describeAction
import `in`.kkkev.jjidea.actions.change.editChangeAction
import `in`.kkkev.jjidea.actions.change.newChangeFromAction
import `in`.kkkev.jjidea.actions.change.rebaseAction
import `in`.kkkev.jjidea.actions.change.resolveConflictsAction
import `in`.kkkev.jjidea.actions.change.splitAction
import `in`.kkkev.jjidea.actions.change.squashAction
import `in`.kkkev.jjidea.actions.change.squashFromAction
import `in`.kkkev.jjidea.actions.change.squashIntoAction
import `in`.kkkev.jjidea.actions.change.squashIntoSources
import `in`.kkkev.jjidea.actions.change.squashableEntry
import `in`.kkkev.jjidea.actions.git.gitFetchAction
import `in`.kkkev.jjidea.actions.git.gitPushAction
import `in`.kkkev.jjidea.actions.git.openInRemoteGroup
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import java.net.URI
import java.net.URLDecoder

/**
 * Context menu actions for the custom Jujutsu log table.
 *
 * Provides actions like Copy Change ID, Copy Description, New Change From This, etc.
 */
object JujutsuLogContextMenuActions {
    /**
     * Create the action group for the context menu.
     * Different actions are shown depending on whether the selected entry is the working copy.
     */
    fun createActionGroup(
        project: Project,
        entries: List<LogEntry>
    ): DefaultActionGroup = DefaultActionGroup().apply {
        ActionManager.getInstance().getAction("Jujutsu.ShowChangesDiff")?.let { add(it) }
        addSeparator()

        val entry = entries.singleOrNull()
        entry?.run { add(copyIdAction(id)) }
        add(copyDescriptionAction(entry?.description?.actual))
        addSeparator()

        // Offer "New Change From This/These" if all entries are in the same root
        val uniqueRepo = entries.map { it.repo }.toSet().singleOrNull()

        add(newChangeFromAction(project, uniqueRepo, entries.map { it.id }))

        // Offer "Edit" for non-working-copy, non-immutable changes
        add(editChangeAction(project, entry?.takeIf { !it.isWorkingCopy && !it.immutable }))

        // Offer "Describe" for mutable changes
        add(describeAction(project, entry?.takeUnless { it.immutable }))

        // Can abandon any mutable change including working copy
        // TODO Allow abandon on multiple if all entries are immutable
        add(abandonChangeAction(project, entry?.takeIf { !it.immutable }))
        add(resolveConflictsAction(project, entry))

        addSeparator()

        // Offer "Rebase" for mutable changes (single or multi-select, same root)
        val mutableEntries = entries.filter { !it.immutable }
        val rebaseRepo = uniqueRepo?.takeIf { mutableEntries.isNotEmpty() }
        add(rebaseAction(project, rebaseRepo, mutableEntries))
        add(squashAction(project, squashableEntry(entry)))
        val squashIntoSrcs = squashIntoSources(entries)
        add(squashIntoAction(project, uniqueRepo?.takeIf { squashIntoSrcs.isNotEmpty() }, squashIntoSrcs))
        add(squashFromAction(project, entry?.takeIf { !it.immutable }))
        add(splitAction(project, entry?.takeIf { !it.immutable }))

        addSeparator()
        add(createBookmarkAction(entry))
        entry?.takeIf { it.bookmarks.isNotEmpty() }?.let { entry ->
            addPopup("action.bookmark.submenu", JujutsuIcons.Bookmark) {
                entry.bookmarks.forEachIndexed { i, bookmark ->
                    if (i > 0) {
                        addSeparator()
                    }
                    if (!bookmark.isRemote) {
                        add(deleteBookmarkAction(entry.repo, bookmark))
                        add(forgetBookmarkAction(entry.repo, bookmark))
                        add(renameBookmarkAction(entry.repo, bookmark))
                    }
                    if (bookmark.isRemote) {
                        add(toggleTrackBookmarkAction(entry.repo, bookmark))
                    }
                }
            }
        }
        add(moveBookmarkAction(entry))

        addSeparator()
        add(gitFetchAction(project, uniqueRepo))
        add(gitPushAction(project, uniqueRepo, entry?.id))
        entry?.let { add(openInRemoteGroup(it.repo, it.commitId, it.immutable)) }
    }

    /** Build the per-bookmark context menu for a right-click on a bookmark chip. */
    fun createBookmarkActionGroup(project: Project, click: BookmarkClick): DefaultActionGroup =
        DefaultActionGroup().apply {
            val repo = click.repo
            val bookmark = click.bookmark
            if (bookmark.isRemote) {
                add(toggleTrackBookmarkAction(repo, bookmark))
            } else {
                add(renameBookmarkAction(repo, bookmark))
                add(deleteBookmarkAction(repo, bookmark))
                add(forgetBookmarkAction(repo, bookmark))
                add(moveBookmarkToChangeAction(repo, bookmark))
            }
        }

    /**
     * Resolve a `jjb://` URI against a [LogEntry]'s bookmark list, returning the
     * [BookmarkClick] for the specific bookmark, or null if the URI is not a `jjb://` link
     * or the bookmark is not found.
     */
    fun resolveBookmarkClick(uri: URI, entry: LogEntry): BookmarkClick? {
        if (uri.scheme != "jjb") return null
        val query = uri.rawQuery ?: return null
        val bookmarkParam = query.substringAfter("bookmark=", "").takeIf { it.isNotEmpty() } ?: return null
        val bookmarkName = URLDecoder.decode(bookmarkParam, "UTF-8")
        val bookmark = entry.bookmarks.find { it.name.name == bookmarkName } ?: return null
        return BookmarkClick(entry.repo, entry, bookmark)
    }
}
