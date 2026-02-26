package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.actions.*

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
        entries: List<LogEntry>,
        allEntries: List<LogEntry> = emptyList()
    ): DefaultActionGroup =
        DefaultActionGroup().apply {
            val entry = entries.singleOrNull()
            entry?.run { add(copyIdAction(id)) }
            add(copyDescriptionAction(entry?.description?.actual))
            addSeparator()

            // Offer "New Change From This/These" if all entries are in the same root
            val uniqueRoot = entries.map { it.repo }.toSet().singleOrNull()

            add(newChangeFromAction(project, uniqueRoot, entries.map { it.id }))

            // Offer "Edit" for non-working-copy, non-immutable changes
            add(editChangeAction(project, entry?.takeIf { !it.isWorkingCopy && !it.immutable }))

            // Offer "Describe" for mutable changes
            add(describeAction(project, entry?.takeUnless { it.immutable }))

            // Can abandon any mutable change including working copy
            // TODO Allow abandon on multiple if all entries are immutable
            add(abandonChangeAction(project, entry?.takeIf { !it.immutable }))

            addSeparator()

            // Offer "Rebase" for mutable changes (single or multi-select, same root)
            val mutableEntries = entries.filter { !it.immutable }
            val rebaseRepo = uniqueRoot?.takeIf { mutableEntries.isNotEmpty() }
            add(rebaseAction(project, rebaseRepo, mutableEntries, allEntries))

            addSeparator()
            add(gitFetchAction(project, uniqueRoot))
            add(gitPushAction(project, uniqueRoot))
        }
}
