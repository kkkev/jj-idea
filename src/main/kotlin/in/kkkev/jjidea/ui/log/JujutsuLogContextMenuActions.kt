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
import `in`.kkkev.jjidea.actions.tag.deleteTagAction
import `in`.kkkev.jjidea.actions.tag.setTagAction
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.JujutsuIcons

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
            addPopup("action.bookmark.submenu", JujutsuIcons.BookmarkAction) {
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

        add(setTagAction(entry))
        entry?.takeIf { it.tags.isNotEmpty() }?.let { e ->
            addPopup("action.tag.submenu", JujutsuIcons.Tag) {
                e.tags.forEachIndexed { i, tag ->
                    if (i > 0) addSeparator()
                    add(deleteTagAction(e.repo, tag))
                }
            }
        }

        addSeparator()
        add(gitFetchAction(project, uniqueRepo))
        add(gitPushAction(project, uniqueRepo, entry?.id))
        entry?.let { add(openInRemoteGroup(it.repo, it.commitId, it.immutable)) }
    }

    /**
     * Build the action group for a right-click on any clickable ref chip (bookmark or tag).
     * This is the single dispatcher — `BookmarkClick` and `TagClick` each get their own menu.
     */
    fun clickActionGroup(project: Project, target: LogClickTarget): DefaultActionGroup =
        DefaultActionGroup().apply {
            when (target) {
                is BookmarkClick -> {
                    val bookmark = target.bookmark
                    if (bookmark.isRemote) {
                        add(toggleTrackBookmarkAction(target.repo, bookmark))
                    } else {
                        add(renameBookmarkAction(target.repo, bookmark))
                        add(deleteBookmarkAction(target.repo, bookmark))
                        add(forgetBookmarkAction(target.repo, bookmark))
                        add(moveBookmarkToChangeAction(target.repo, bookmark))
                    }
                }
                is TagClick -> add(deleteTagAction(target.repo, target.tag))
                // MoreRefsClick (the "+N more" overflow chip, jj-idea-w61m) is handled separately by
                // JujutsuLogTable, which shows a popup over the hidden refs instead of this menu.
                is MoreRefsClick -> Unit
            }
        }
}
