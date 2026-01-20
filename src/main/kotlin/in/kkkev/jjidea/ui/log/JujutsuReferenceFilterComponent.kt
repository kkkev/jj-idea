package `in`.kkkev.jjidea.ui.log

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.ChangeId
import javax.swing.Icon

/**
 * Filter component for references (bookmarks, tags, and @).
 * When a reference is selected, includes all parent commits of that reference.
 */
class JujutsuReferenceFilterComponent(private val tableModel: JujutsuLogTableModel) :
    JujutsuFilterComponent(JujutsuBundle.message("log.filter.reference")) {
    private var selectedReference: String? = null

    override fun getCurrentText(): String = selectedReference ?: ""

    override fun isValueSelected(): Boolean = selectedReference != null

    fun initialize() {
        addChangeListener {
            applyFilter()
        }
    }

    override fun createActionGroup(): ActionGroup {
        val group = DefaultActionGroup()

        // Get all references from the table model
        val references = getAllReferences()

        // Add @ (working copy) if it exists
        if (references.workingCopy != null) {
            group.add(SelectReferenceAction("@", ReferenceType.WORKING_COPY))
        }

        // Add bookmarks
        references.bookmarks.forEach { bookmark ->
            group.add(SelectReferenceAction(bookmark, ReferenceType.BOOKMARK))
        }

        // Add tags
        references.tags.forEach { tag ->
            group.add(SelectReferenceAction(tag, ReferenceType.TAG))
        }

        // Add clear option if reference is selected
        if (selectedReference != null) {
            group.addSeparator()
            group.add(ClearFilterAction())
        }

        return group
    }

    override fun doResetFilter() {
        selectedReference = null
        notifyFilterChanged()
    }

    private fun applyFilter() {
        if (selectedReference != null) {
            // Get all change IDs that are ancestors of the selected reference
            val ancestorIds = getAncestorChangeIds(selectedReference!!)
            tableModel.setBookmarkFilter(ancestorIds)
        } else {
            tableModel.setBookmarkFilter(emptySet())
        }
    }

    /**
     * Get all references (working copy and bookmarks) from the log entries.
     * TODO: Add tags support when template is updated to extract tags.
     */
    private fun getAllReferences(): References {
        val allEntries = tableModel.getAllEntries()
        val bookmarks = mutableSetOf<String>()
        var workingCopy: String? = null

        allEntries.forEach { entry ->
            // Check for working copy
            if (entry.isWorkingCopy) {
                workingCopy = "@"
            }

            // Collect bookmarks (tags not yet supported in log template)
            entry.bookmarks.forEach { bookmark ->
                bookmarks.add(bookmark.name)
            }
        }

        return References(
            workingCopy = workingCopy,
            bookmarks = bookmarks.sorted(),
            tags = emptyList()
        )
    }

    /**
     * Get all change IDs that are ancestors of the given reference.
     * This includes the commit with the reference and all its parents recursively.
     */
    private fun getAncestorChangeIds(referenceName: String): Set<ChangeId> {
        val allEntries = tableModel.getAllEntries()
        val result = mutableSetOf<ChangeId>()
        val toVisit = mutableSetOf<ChangeId>()

        // Find the commit with the reference
        val referencedEntry = allEntries.find { entry ->
            // Check if it's the working copy
            if (referenceName == "@" && entry.isWorkingCopy) {
                return@find true
            }
            // Check if it has the bookmark/tag
            entry.bookmarks.any { it.name == referenceName }
        } ?: return emptySet()

        // Start with the referenced commit
        toVisit.add(referencedEntry.changeId)

        // BFS to collect all ancestors
        while (toVisit.isNotEmpty()) {
            val current = toVisit.first()
            toVisit.remove(current)

            if (result.contains(current)) continue
            result.add(current)

            // Find entry for current changeId
            val entry = allEntries.find { it.changeId == current } ?: continue

            // Add parents to visit
            toVisit.addAll(entry.parentIds)
        }

        return result
    }

    private inner class SelectReferenceAction(
        private val reference: String,
        private val type: ReferenceType
    ) : ToggleAction(reference, null, type.icon) {
        override fun isSelected(e: AnActionEvent): Boolean = selectedReference == reference

        override fun setSelected(
            e: AnActionEvent,
            state: Boolean
        ) {
            selectedReference = if (state) reference else null
            notifyFilterChanged()
        }
    }

    private inner class ClearFilterAction : AnAction(JujutsuBundle.message("log.filter.clear")) {
        override fun actionPerformed(e: AnActionEvent) {
            doResetFilter()
        }
    }

    private data class References(val workingCopy: String?, val bookmarks: List<String>, val tags: List<String>)

    private enum class ReferenceType(val icon: Icon) {
        WORKING_COPY(AllIcons.Vcs.Branch), // @ symbol for working copy
        BOOKMARK(AllIcons.Vcs.Branch),
        TAG(AllIcons.Nodes.Tag) // For future use when tags are supported
    }
}
