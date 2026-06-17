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
 * Filter component for references (bookmarks, tags, and @).
 * When a reference is selected, includes all parent commits of that reference.
 * Out-of-limit references trigger a context-window expansion (same as navigate-to-out-of-limit).
 *
 * Bookmark and tag lists are sourced from [in.kkkev.jjidea.jj.JujutsuStateModel.references]
 * and kept current automatically — no background loading is needed here.
 */
class JujutsuReferenceFilterComponent(
    private val tableModel: JujutsuLogTableModel,
    private val project: Project,
    parentDisposable: Disposable
) : JujutsuFilterComponent(JujutsuBundle.message("log.filter.reference")), Disposable {
    private data class SelectedRef(val name: String, val type: ReferenceType)

    private var selectedReference: SelectedRef? = null

    private var allBookmarkNames: Set<String> = emptySet()
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
        allBookmarkNames = references.values.flatMap { it.bookmarks }.map { it.bookmark.name.name }.toSet()
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
     * Pre-sets the selected reference from a persisted name so it is applied when [retryFilter] is
     * called from [onDataLoaded]. Determines the reference type from the known bookmark/tag lists;
     * falls back to BOOKMARK for unknown names (e.g., stale state before the lists are populated).
     */
    fun setInitialReference(name: String) {
        if (name.isEmpty()) return
        val type = when {
            name == "@" -> ReferenceType.WORKING_COPY
            name in allTagNames -> ReferenceType.TAG
            else -> ReferenceType.BOOKMARK
        }
        selectedReference = SelectedRef(name, type)
        // Refresh the button icon and label without calling notifyFilterChanged(), because at init
        // time the table model is empty and calling applyFilter() would spuriously set
        // expansionInFlight=true before onReferenceExpansionNeeded is wired up.
        refreshPresentation()
    }

    override fun createActionGroup(): ActionGroup {
        val group = BackgroundActionGroup()
        val references = getAllReferences()

        if (references.workingCopy != null) {
            group.add(SelectReferenceAction(WorkingCopy.REF, ReferenceType.WORKING_COPY))
        }
        references.bookmarks.forEach { group.add(SelectReferenceAction(it, ReferenceType.BOOKMARK)) }
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
        val ref = selectedReference ?: run {
            tableModel.setBookmarkFilter(emptySet())
            return
        }
        val ancestorIds = getAncestorIds(ref)
        when {
            ancestorIds != null -> {
                expansionInFlight = false
                tableModel.setBookmarkFilter(ancestorIds)
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
            bookmarks = allBookmarkNames.sorted(),
            tags = allTagNames.sorted()
        )
    }

    private fun getAncestorIds(ref: SelectedRef): Set<ChangeId>? {
        val allEntries = tableModel.getAllEntries()
        val result = mutableSetOf<ChangeId>()
        val toVisit = mutableSetOf<ChangeId>()

        val referencedEntry = allEntries.find { entry ->
            when (ref.type) {
                ReferenceType.WORKING_COPY -> entry.isWorkingCopy
                ReferenceType.BOOKMARK -> entry.bookmarks.any { it.name.name == ref.name }
                ReferenceType.TAG -> entry.tags.any { it.name == ref.name }
            }
        } ?: return null

        toVisit.add(referencedEntry.id)
        while (toVisit.isNotEmpty()) {
            val current = toVisit.first()
            toVisit.remove(current)
            if (current !in result) {
                result.add(current)
                allEntries.find { it.id == current }?.let { toVisit.addAll(it.parentIds) }
            }
        }
        return result
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

    private data class References(val workingCopy: String?, val bookmarks: List<String>, val tags: List<String>)

    // BookmarkAction (not the narrower inline-text Bookmark icon) is already sized for AnAction
    // icon slots — its viewBox reserves headroom so the glyph renders centered in a true 16x16
    // box instead of being stretched to fill it, matching JujutsuIcons.Tag and AllIcons.Vcs.Branch.
    private enum class ReferenceType(val icon: Icon) {
        WORKING_COPY(AllIcons.Vcs.Branch),
        BOOKMARK(JujutsuIcons.BookmarkAction.accented(JujutsuColors.BOOKMARK)),
        TAG(JujutsuIcons.Tag.accented(JujutsuColors.TAG))
    }

    override fun dispose() = Unit
}
