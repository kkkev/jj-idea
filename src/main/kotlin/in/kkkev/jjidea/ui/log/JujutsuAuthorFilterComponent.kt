package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.*
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.vcs.actions.BackgroundActionGroup

/**
 * Filter component for authors.
 */
class JujutsuAuthorFilterComponent(private val tableModel: JujutsuLogTableModel) :
    JujutsuFilterComponent(JujutsuBundle.message("log.filter.author")) {
    private val selectedAuthors = mutableSetOf<String>()

    override fun getCurrentText(): String = when (selectedAuthors.size) {
        0 -> ""
        1 -> selectedAuthors.first()
        else -> JujutsuBundle.message("log.filter.multiple", selectedAuthors.size)
    }

    override fun isValueSelected(): Boolean = selectedAuthors.isNotEmpty()

    fun initialize() {
        addChangeListener {
            tableModel.setAuthorFilter(selectedAuthors)
        }
    }

    override fun createActionGroup(): ActionGroup {
        val group = BackgroundActionGroup()

        // Add author options
        val authors = tableModel.getAllAuthors()
        authors.forEach { author ->
            group.add(ToggleAuthorAction(author))
        }

        // Add clear option if authors are selected
        if (selectedAuthors.isNotEmpty()) {
            group.addSeparator()
            group.add(ClearFilterAction())
        }

        return group
    }

    override fun doResetFilter() {
        selectedAuthors.clear()
        notifyFilterChanged()
    }

    private inner class ToggleAuthorAction(private val author: String) : ToggleAction(author) {
        override fun isSelected(e: AnActionEvent): Boolean = selectedAuthors.contains(author)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) {
                selectedAuthors.add(author)
            } else {
                selectedAuthors.remove(author)
            }
            notifyFilterChanged()
        }
    }

    private inner class ClearFilterAction : AnAction(JujutsuBundle.message("log.filter.clear")) {
        override fun actionPerformed(e: AnActionEvent) {
            doResetFilter()
        }
    }
}
