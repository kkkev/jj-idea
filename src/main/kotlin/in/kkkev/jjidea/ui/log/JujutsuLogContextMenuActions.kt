package `in`.kkkev.jjidea.ui.log

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project
import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.actions.addPopup
import `in`.kkkev.jjidea.actions.bookmark.createBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.deleteBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.forgetBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.moveBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.moveBookmarkToChangeAction
import `in`.kkkev.jjidea.actions.bookmark.renameBookmarkAction
import `in`.kkkev.jjidea.actions.bookmark.toggleTrackBookmarkAction
import `in`.kkkev.jjidea.actions.change.abandonChangeAction
import `in`.kkkev.jjidea.actions.change.compareWithWorkingCopyAction
import `in`.kkkev.jjidea.actions.change.copyDescriptionAction
import `in`.kkkev.jjidea.actions.change.copyIdAction
import `in`.kkkev.jjidea.actions.change.describeAction
import `in`.kkkev.jjidea.actions.change.duplicateChangeAction
import `in`.kkkev.jjidea.actions.change.duplicateOntoAction
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
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import java.net.URI

/**
 * Marker for the action in a [JujutsuLogContextMenuActions.clickActionGroup] menu that mirrors
 * the left-click default action for that log-row element (jj-idea-iesq) — used by
 * `JujutsuLogTable`'s right-click popup to pre-select it, so opening the menu reveals what
 * left-click does.
 */
interface DefaultClickAction

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
    ): DefaultActionGroup = BackgroundActionGroup().apply {
        ActionManager.getInstance().getAction("Jujutsu.ShowChangesDiff")?.let { add(it) }

        val entry = entries.singleOrNull()
        add(compareWithWorkingCopyAction(project, entry?.takeIf { !it.isWorkingCopy }))
        addSeparator()

        entry?.run { add(copyIdAction(id)) }
        add(copyDescriptionAction(entry?.description?.actual))
        addSeparator()

        // Offer "New Change From This/These" if all entries are in the same root.
        // The quick (no-dialog) action is primary and shows the default keybinding; the
        // dialog-based variant is offered as a secondary "with description" option.
        val uniqueRepo = entries.map { it.repo }.toSet().singleOrNull()

        ActionManager.getInstance().getAction("Jujutsu.NewChange")?.let { add(it) }
        add(newChangeFromAction(project, uniqueRepo, entries.map { it.id }))

        // Offer "Edit" for non-working-copy, non-immutable changes
        ActionManager.getInstance().getAction("Jujutsu.EditChange")?.let { add(it) }

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

        // Duplicate works on any change, including immutable ones
        add(duplicateChangeAction(project, uniqueRepo, entries))
        add(duplicateOntoAction(project, uniqueRepo, entries))

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
     * Build the action group for a right-click on any clickable log-row element (ref chip or
     * author/committer name). This is the single dispatcher — `BookmarkClick`, `TagClick`, and
     * `PersonClick` each get their own menu. The first action in each menu (marked
     * [DefaultClickAction]) is pre-selected by `JujutsuLogTable`'s popup (jj-idea-iesq). For
     * `PersonClick` this still mirrors the element's left-click default (open the mail client);
     * bookmark/tag chips have no left-click action of their own (jj-idea-wkcz), so their
     * `DefaultClickAction` (filter to reference) is just the most useful entry to pre-highlight.
     *
     * The "Filter Log to '...'"/"Filter Log by ..." checkmark reads
     * [in.kkkev.jjidea.jj.JujutsuStateModel.activeReferenceFilter]/`activeAuthorFilter` directly
     * from [project] — a single project-level source of truth for "what's the active filter"
     * rather than a value threaded in by each caller (`JujutsuLogTable`,
     * `JujutsuCommitDetailsPanel`), which would otherwise need to know this state exists purely to
     * pass it along.
     */
    fun clickActionGroup(project: Project, target: LogClickTarget): DefaultActionGroup =
        BackgroundActionGroup().apply {
            when (target) {
                is BookmarkClick -> {
                    val name = target.bookmark.name.name
                    add(FilterToReferenceAction(project, name))
                    addSeparator()
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
                is TagClick -> {
                    add(FilterToReferenceAction(project, target.tag.name))
                    addSeparator()
                    add(deleteTagAction(target.repo, target.tag))
                }
                is PersonClick -> {
                    add(SendEmailAction(target.user))
                    if (target.canFilter) {
                        addSeparator()
                        add(FilterByAuthorAction(project, target.user))
                    }
                }
                is IssueLinkClick -> add(OpenIssueLinkAction(target.uri))
                // jjc:// change-navigation links have never had a right-click menu (their default,
                // and only, action is left-click navigation - see LogClickTarget.performDefaultAction).
                is ChangeNavigationClick -> Unit
                // MoreRefsClick (the "+N more" overflow chip, jj-idea-w61m) is handled separately by
                // JujutsuLogTable, which shows a popup over the hidden refs instead of this menu.
                is MoreRefsClick -> Unit
            }
        }

    /**
     * Right-click default for a bookmark/tag chip (jj-idea-wkcz — chips have no left-click action):
     * narrow the log to that reference and its ancestors, or clear the filter if [name] is already
     * the active one — toggled by whichever `filterToReference` listener is wired up (see
     * `CommitTablePanel.createFilterComponents`). Checked when [name] is
     * [in.kkkev.jjidea.jj.JujutsuStateModel.activeReferenceFilter].
     */
    private class FilterToReferenceAction(
        private val project: Project,
        private val name: String
    ) : ToggleAction(JujutsuBundle.message("log.click.filterToReference", name)), DefaultClickAction {
        init {
            // Close on click like every other action in this menu (rename/delete/etc.), instead of
            // ToggleAction's default checkbox-style "stay open" behavior - this is a one-shot
            // apply/clear action, not a persistent multi-select list. Also sidesteps a checkmark
            // staleness issue: filterToReference.notify() defers its actual state change via
            // runLater, so an immediate re-query of isSelected() while the popup stayed open would
            // still see the pre-click state.
            templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Never
        }

        override fun isSelected(e: AnActionEvent) = project.stateModel.activeReferenceFilter == name

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            project.stateModel.filterToReference.notify(name)
        }
    }

    /** Left-click default for an author/committer name: open the OS mail client. */
    private class SendEmailAction(private val user: VcsUser) :
        AnAction(JujutsuBundle.message("log.click.sendEmail", user.name)),
        DefaultClickAction {
        override fun actionPerformed(e: AnActionEvent) {
            BrowserUtil.browse(URI("mailto", user.email, null))
        }
    }

    /**
     * Left-click default for a linkified issue-tracker reference (e.g. `JIRA-123`) inside the
     * description or a bookmark/tag chip label: open the tracker URL (jj-idea-91qf, jj-idea-vrmv).
     */
    private class OpenIssueLinkAction(private val uri: URI) :
        AnAction(JujutsuBundle.message("log.click.openIssueLink", uri)),
        DefaultClickAction {
        override fun actionPerformed(e: AnActionEvent) {
            BrowserUtil.browse(uri)
        }
    }

    /**
     * Right-click-only extra for the author column: narrow the log to commits by this author.
     * Checked when this author's email is in
     * [in.kkkev.jjidea.jj.JujutsuStateModel.activeAuthorFilter].
     */
    private class FilterByAuthorAction(private val project: Project, private val user: VcsUser) :
        ToggleAction(JujutsuBundle.message("log.click.filterByAuthor", user.name)) {
        init {
            // See FilterToReferenceAction's init for why: close on click rather than
            // ToggleAction's default "stay open" checkbox behavior.
            templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Never
        }

        override fun isSelected(e: AnActionEvent) = project.stateModel.activeAuthorFilter == setOf(user.email)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            project.stateModel.filterByAuthor.notify(user.email)
        }
    }
}
