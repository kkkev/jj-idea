package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.*
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.BackgroundActionGroup
import `in`.kkkev.jjidea.jj.JujutsuRepository
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * Filter component for repository roots.
 *
 * Allows filtering commits by which repository root they belong to.
 * Only shown when there are multiple roots in the project.
 *
 * Each root shows a colored square icon matching the gutter color.
 */
class JujutsuRootFilterComponent(private val tableModel: JujutsuLogTableModel) :
    JujutsuFilterComponent(JujutsuBundle.message("log.filter.root")) {
    private val selectedRoots = mutableSetOf<JujutsuRepository>()

    override fun getCurrentText(): String = when (selectedRoots.size) {
        0 -> JujutsuBundle.message("log.filter.root.all")
        1 -> selectedRoots.first().displayName
        else -> JujutsuBundle.message("log.filter.multiple", selectedRoots.size)
    }

    override fun isValueSelected(): Boolean = selectedRoots.isNotEmpty()

    fun initialize() {
        addChangeListener {
            tableModel.setRootFilter(selectedRoots)
        }
    }

    /**
     * Check if this filter should be visible.
     * Only show when there are multiple roots.
     */
    fun shouldBeVisible(): Boolean = tableModel.getAllRoots().size > 1

    override fun createActionGroup(): ActionGroup {
        val group = BackgroundActionGroup()

        // Add root options
        val roots = tableModel.getAllRoots()
        roots.forEach { root ->
            group.add(ToggleRootAction(root))
        }

        // Add clear option if roots are selected (consistent with other filters)
        if (selectedRoots.isNotEmpty()) {
            group.addSeparator()
            group.add(ClearFilterAction())
        }

        return group
    }

    override fun doResetFilter() {
        selectedRoots.clear()
        notifyFilterChanged()
    }

    private inner class ToggleRootAction(private val root: JujutsuRepository) : ToggleAction(
        root.displayName,
        null,
        null
    ) {
        private val color = RepositoryColors.getColor(root)
        private val selectedIcon = rootIcon(color, true)
        private val deselectedIcon = rootIcon(color, false)

        init {
            // Reserve space for icon - see PopupFactoryImpl.calcMaxIconSize
            templatePresentation.icon = EmptyIcon.create(JBUI.scale(15))
        }

        override fun isSelected(e: AnActionEvent): Boolean = selectedRoots.contains(root)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) {
                selectedRoots.add(root)
            } else {
                selectedRoots.remove(root)
            }
            notifyFilterChanged()
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            // Set the colored checkbox icon based on selection state
            e.presentation.icon = if (isSelected(e)) selectedIcon else deselectedIcon
        }
    }

    private inner class ClearFilterAction : AnAction(JujutsuBundle.message("log.filter.clear")) {
        override fun actionPerformed(e: AnActionEvent) {
            doResetFilter()
        }
    }
}

private fun rootIcon(color: Color, selected: Boolean): Icon {
    val size = JBUI.scale(10)
    val arc = JBUI.scale(3)
    return object : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            if (selected) {
                g2.fillRoundRect(x, y, size, size, arc, arc)
            } else {
                g2.drawRoundRect(x, y, size - 1, size - 1, arc, arc)
            }
            g2.dispose()
        }

        override fun getIconWidth() = size
        override fun getIconHeight() = size
    }
}
