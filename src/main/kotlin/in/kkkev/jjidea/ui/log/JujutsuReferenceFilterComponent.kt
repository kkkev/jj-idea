package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.ui.RowIcon
import com.intellij.util.ui.EmptyIcon
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.jj.*
import `in`.kkkev.jjidea.ui.common.JujutsuColors
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.common.accented
import javax.swing.Icon

// Hoisted to avoid rebuilding on every row update.
private val CHECK_ICON = AllIcons.Actions.Checked
private val EMPTY_CHECK_ICON = EmptyIcon.create(CHECK_ICON.iconWidth, CHECK_ICON.iconHeight)

/**
 * Reduces a flat, possibly cross-repo list of bookmarks to the set of names selectable in the
 * References filter, mapped to whether each is remote-only (no local counterpart). Deleted
 * bookmarks are excluded. Bookmarks are grouped by [BookmarkGroup.localName] first so a remote
 * that is merely synced with its local counterpart (e.g. `main` + `main@origin` pointing at the
 * same target) collapses into a single `main` entry instead of showing twice.
 */
internal fun selectableBookmarkNames(bookmarks: List<Bookmark>): Map<String, Boolean> =
    bookmarks.filterNot { it.deleted }.grouped().flatMap { group ->
        if (group.local != null) {
            listOf(group.localName to false)
        } else {
            group.remotes.map { it.name.name to true }
        }
    }.toMap()

/**
 * Walks the ancestry of every entry in [entries] satisfying [matches], returning the repo-scoped
 * keys of the matched entries and all their ancestors, or `null` if nothing matched (the caller
 * treats `null` as "not loaded yet, trigger an expansion").
 *
 * Scoped to [ChangeKey] (not a bare [ChangeId]) throughout: jj's root commit has the identical
 * change ID ("zzzzzzzz...") in every repository since it's synthetic rather than
 * content-derived, so a bare-id walk would cross into another repo's ancestry once it reaches a
 * root (jj-idea-1ra9). Seeding from *every* match, not just the first, also means the same
 * bookmark/tag name in two repos - or `@` in a multi-repo project - returns both repos' ancestries
 * instead of only the first repo's (jj-idea-2xf3).
 */
internal fun ancestorKeys(entries: List<LogEntry>, matches: (LogEntry) -> Boolean): Set<ChangeKey>? {
    val entryByKey = entries.associateBy { it.key }
    val seeds = entries.filter(matches).map { it.key }
    if (seeds.isEmpty()) return null

    val result = mutableSetOf<ChangeKey>()
    val toVisit = ArrayDeque(seeds)
    while (toVisit.isNotEmpty()) {
        val current = toVisit.removeFirst()
        if (result.add(current)) {
            entryByKey[current]?.let { toVisit.addAll(it.parentKeys) }
        }
    }
    return result
}

/**
 * Filter component for references (bookmarks, tags, and @).
 * When a reference is selected, includes all parent commits of that reference.
 * Out-of-limit references trigger a context-window expansion (same as navigate-to-out-of-limit).
 *
 * Bookmark and tag lists are sourced from [in.kkkev.jjidea.jj.JujutsuStateModel.references]
 * and kept current automatically — no background loading is needed here. While that first load is
 * still in flight, the dropdown shows a disabled "Loading…" placeholder instead of looking like an
 * empty (bookmark/tag-free) repo (jj-idea-a52h).
 */
class JujutsuReferenceFilterComponent(
    private val tableModel: JujutsuLogTableModel,
    private val project: Project,
    parentDisposable: Disposable
) : JujutsuFilterComponent(JujutsuBundle.message("log.filter.reference")), Disposable {
    private data class SelectedRef(val name: String, val type: ReferenceType)

    private var selectedReference: SelectedRef? = null

    // Bookmark ref name -> BOOKMARK (local) or REMOTE_BOOKMARK (remote-only, no local counterpart).
    // Synced tracked-remote counterparts of a local bookmark are collapsed into the local entry.
    private var bookmarkRefs: Map<String, ReferenceType> = emptyMap()
    private var allTagNames: Set<String> = emptySet()

    // Prevents re-triggering loadExpanding while one is already in flight.
    private var expansionInFlight = false

    /**
     * Invoked when the selected reference is not in the current loaded entries and needs expanding.
     * The handler should trigger a context-window load and call [retryFilter] when done.
     */
    var onReferenceExpansionNeeded: ((String) -> Unit)? = null

    init {
        updateFromReferences(project.stateModel.references.value)
        project.stateModel.references.connect(parentDisposable) { updateFromReferences(it) }
    }

    private fun updateFromReferences(references: Map<JujutsuRepository, RepositoryReferences>) {
        val bookmarks = references.values.flatMap { it.bookmarks }.map { it.bookmark }
        bookmarkRefs = selectableBookmarkNames(bookmarks).mapValues { (_, remoteOnly) ->
            if (remoteOnly) ReferenceType.REMOTE_BOOKMARK else ReferenceType.BOOKMARK
        }
        allTagNames = references.values.flatMap { it.tags }.map { it.tag.name }.toSet()
        repaint()
    }

    override fun getCurrentText(): String = selectedReference?.name ?: ""

    override fun isValueSelected(): Boolean = selectedReference != null

    fun initialize() {
        addChangeListener { applyFilter() }
    }

    /** Re-run the current filter — called by the panel after an expansion completes. */
    fun retryFilter() {
        if (selectedReference != null) applyFilter()
    }

    /** Returns the name of the currently selected reference, or "" if none. */
    fun getSelectedReferenceName(): String = selectedReference?.name ?: ""

    /**
     * Select and apply a reference filter by [name] (bookmark, tag, or "@"; type auto-detected
     * the same way [setInitialReference] does). Used to drive the filter programmatically, e.g.
     * from a clicked bookmark/tag chip (jj-idea-iesq). A no-op for an empty name.
     */
    fun selectReference(name: String) {
        if (name.isEmpty()) return
        setInitialReference(name)
        expansionInFlight = false
        notifyFilterChanged()
    }

    /**
     * Clear the reference filter. Used to toggle a filter off when the same bookmark/tag chip or
     * "Filter Log to..." action is triggered again while it's already the active filter
     * (jj-idea-iesq), mirroring familiar toggle-button UX.
     */
    fun clearReference() = doResetFilter()

    /**
     * Pre-sets the selected reference from a persisted name so it is applied when [retryFilter] is
     * called from [onDataLoaded]. Determines the reference type from the known bookmark/tag lists;
     * falls back to BOOKMARK for unknown names (e.g., stale state before the lists are populated).
     */
    fun setInitialReference(name: String) {
        if (name.isEmpty()) return
        val type = when {
            name == "@" -> ReferenceType.WORKING_COPY
            name in allTagNames -> ReferenceType.TAG
            else -> bookmarkRefs[name] ?: ReferenceType.BOOKMARK
        }
        selectedReference = SelectedRef(name, type)
        // Refresh the button icon and label without calling notifyFilterChanged(), because at init
        // time the table model is empty and calling applyFilter() would spuriously set
        // expansionInFlight=true before onReferenceExpansionNeeded is wired up.
        refreshPresentation()
    }

    override fun createActionGroup(): ActionGroup {
        val group = BackgroundActionGroup()

        // Bookmarks/tags load on a pooled thread and can genuinely still be empty right after the
        // log opens (jj-idea-a52h) - without this, that transient state is indistinguishable from
        // "this repo really has no bookmarks or tags".
        if (!project.stateModel.references.hasLoaded) {
            group.add(LoadingReferencesAction())
            group.addSeparator()
        }

        val references = getAllReferences()

        if (references.workingCopy != null) {
            group.add(SelectReferenceAction(WorkingCopy.REF, ReferenceType.WORKING_COPY))
        }
        references.bookmarks.forEach { (name, type) -> group.add(SelectReferenceAction(name, type)) }
        references.tags.forEach { group.add(SelectReferenceAction(it, ReferenceType.TAG)) }

        if (selectedReference != null) {
            group.addSeparator()
            group.add(ClearFilterAction())
        }

        return group
    }

    override fun doResetFilter() {
        selectedReference = null
        expansionInFlight = false
        notifyFilterChanged()
    }

    override fun preselectCondition(): Condition<in AnAction>? {
        val sel = selectedReference ?: return null
        return Condition { it is SelectReferenceAction && it.ref == sel }
    }

    private fun applyFilter() {
        // Single choke point for syncing the project-wide "active reference filter" state
        // (jj-idea-iesq) - every mutation of selectedReference (dropdown pick, clear, or
        // selectReference()'s toggle) ends up here via notifyFilterChanged().
        project.stateModel.activeReferenceFilter = selectedReference?.name ?: ""
        val ref = selectedReference ?: run {
            tableModel.setBookmarkFilter(emptySet())
            return
        }
        val ancestorKeys = getAncestorKeys(ref)
        when {
            ancestorKeys != null -> {
                expansionInFlight = false
                tableModel.setBookmarkFilter(ancestorKeys)
            }

            !expansionInFlight -> {
                expansionInFlight = true
                tableModel.setBookmarkFilter(emptySet())
                onReferenceExpansionNeeded?.invoke(ref.name)
            }
            // expansion already in flight — wait for retryFilter()
        }
    }

    private fun getAllReferences(): References {
        val allEntries = tableModel.getAllEntries()
        var workingCopy: String? = null
        allEntries.forEach { if (it.isWorkingCopy) workingCopy = WorkingCopy.REF }
        return References(
            workingCopy = workingCopy,
            bookmarks = bookmarkRefs.entries.sortedBy { it.key }.map { it.key to it.value },
            tags = allTagNames.sorted()
        )
    }

    private fun getAncestorKeys(ref: SelectedRef): Set<ChangeKey>? =
        ancestorKeys(tableModel.getAllEntries()) { entry ->
            when (ref.type) {
                ReferenceType.WORKING_COPY -> entry.isWorkingCopy
                ReferenceType.BOOKMARK, ReferenceType.REMOTE_BOOKMARK ->
                    entry.bookmarks.any { it.name.name == ref.name }
                ReferenceType.TAG -> entry.tags.any { it.name == ref.name }
            }
        }

    private inner class SelectReferenceAction(private val reference: String, private val type: ReferenceType) :
        ToggleAction(reference) {
        val ref get() = SelectedRef(reference, type)

        init {
            // Single-select: clicking an entry should apply it and close the popup immediately,
            // not keep it open the way multi-select toggles (e.g. JujutsuRootFilterComponent) do.
            templatePresentation.keepPopupOnPerform = KeepPopupOnPerform.Never
        }

        override fun isSelected(e: AnActionEvent): Boolean = selectedReference == ref

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            selectedReference = if (state) ref else null
            expansionInFlight = false
            notifyFilterChanged()
        }

        // ToggleAction.update() nulls the icon in popups to show its own checkmark instead
        // (ActionStepBuilder.calcRawIcons only synthesizes a checkmark when no icon is set).
        // Overwrite it afterwards with a checkmark + type icon composed ourselves, the same way
        // JujutsuRootFilterComponent.ToggleRootAction overwrites its icon after super.update() —
        // this also makes the marker persist through mouse hover and keyboard navigation, unlike
        // the popup's transient pre-selection highlight.
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.icon = RowIcon(if (isSelected(e)) CHECK_ICON else EMPTY_CHECK_ICON, type.icon)
        }
    }

    private inner class ClearFilterAction : AnAction(JujutsuBundle.message("log.filter.clear")) {
        override fun actionPerformed(e: AnActionEvent) = doResetFilter()
    }

    /** Disabled placeholder shown while [in.kkkev.jjidea.jj.JujutsuStateModel.references] is still
     * loading (jj-idea-a52h), so an empty dropdown isn't mistaken for "this repo has no bookmarks
     * or tags". */
    private class LoadingReferencesAction : AnAction(JujutsuBundle.message("log.filter.reference.loading")) {
        init {
            templatePresentation.isEnabled = false
        }

        override fun actionPerformed(e: AnActionEvent) = Unit
    }

    private data class References(
        val workingCopy: String?,
        val bookmarks: List<Pair<String, ReferenceType>>,
        val tags: List<String>
    )

    // BookmarkAction (not the narrower inline-text Bookmark icon) is already sized for AnAction
    // icon slots — its viewBox reserves headroom so the glyph renders centered in a true 16x16
    // box instead of being stretched to fill it, matching JujutsuIcons.Tag and AllIcons.Vcs.Branch.
    // REMOTE_BOOKMARK uses the plain (untracked-style) icon vs. BOOKMARK's filled icon, mirroring
    // the tracked/untracked distinction TextCanvas.appendBookmarkChip draws in the log itself —
    // the "@remote" suffix in the label spells it out further.
    private enum class ReferenceType(val icon: Icon) {
        WORKING_COPY(AllIcons.Vcs.Branch),
        BOOKMARK(JujutsuIcons.BookmarkTrackedAction.accented(JujutsuColors.BOOKMARK)),
        REMOTE_BOOKMARK(JujutsuIcons.BookmarkAction.accented(JujutsuColors.BOOKMARK)),
        TAG(JujutsuIcons.Tag.accented(JujutsuColors.TAG))
    }

    override fun dispose() = Unit
}
