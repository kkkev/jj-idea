package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.jj.*
import javax.swing.Icon

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
    private var selectedReference: String? = null

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

    override fun getCurrentText(): String = selectedReference ?: ""

    override fun isValueSelected(): Boolean = selectedReference != null

    fun initialize() {
        addChangeListener { applyFilter() }
    }

    /** Re-run the current filter — called by the panel after an expansion completes. */
    fun retryFilter() {
        if (selectedReference != null) applyFilter()
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

    private fun applyFilter() {
        if (selectedReference == null) {
            tableModel.setBookmarkFilter(emptySet())
            return
        }
        val ancestorIds = getAncestorIds(selectedReference!!)
        when {
            ancestorIds != null -> {
                expansionInFlight = false
                tableModel.setBookmarkFilter(ancestorIds)
            }

            !expansionInFlight -> {
                expansionInFlight = true
                tableModel.setBookmarkFilter(emptySet())
                onReferenceExpansionNeeded?.invoke(selectedReference!!)
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

    private fun getAncestorIds(referenceName: String): Set<ChangeId>? {
        val allEntries = tableModel.getAllEntries()
        val result = mutableSetOf<ChangeId>()
        val toVisit = mutableSetOf<ChangeId>()

        val referencedEntry = allEntries.find { entry ->
            if (referenceName == WorkingCopy.REF && entry.isWorkingCopy) return@find true
            entry.bookmarks.any { it.name.name == referenceName } ||
                entry.tags.any { it.name == referenceName }
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

    private inner class SelectReferenceAction(private val reference: String, type: ReferenceType) :
        ToggleAction(reference, null, type.icon) {
        override fun isSelected(e: AnActionEvent): Boolean = selectedReference == reference
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            selectedReference = if (state) reference else null
            expansionInFlight = false
            notifyFilterChanged()
        }
    }

    private inner class ClearFilterAction : AnAction(JujutsuBundle.message("log.filter.clear")) {
        override fun actionPerformed(e: AnActionEvent) = doResetFilter()
    }

    private data class References(val workingCopy: String?, val bookmarks: List<String>, val tags: List<String>)

    private enum class ReferenceType(val icon: Icon) {
        WORKING_COPY(AllIcons.Vcs.Branch),
        BOOKMARK(AllIcons.Vcs.Branch),
        TAG(AllIcons.Nodes.Tag)
    }

    override fun dispose() = Unit
}
