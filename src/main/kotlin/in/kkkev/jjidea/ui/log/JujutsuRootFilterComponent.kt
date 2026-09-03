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
 * Allows filtering commits by which repository root they belong to. Only shown when there
 * are multiple roots in the project.
 *
 * Each root cycles through three states (jj-idea-qcks, GitHub #96): unset -> included ->
 * excluded -> unset. With any root included, only included roots show (allowlist);
 * with none included but some excluded, everything but the excluded roots shows
 * (denylist/mute-list) - see [RootFilterSelection] for the rationale. Each root shows a
 * colored square icon matching the gutter color: filled when included, outlined when
 * unset, outlined-and-struck-through when excluded.
 */
class JujutsuRootFilterComponent(private val tableModel: JujutsuLogTableModel) :
    JujutsuFilterComponent(JujutsuBundle.message("log.filter.root")) {
    private val includedRoots = mutableSetOf<JujutsuRepository>()
    private val excludedRoots = mutableSetOf<JujutsuRepository>()

    override fun getCurrentText(): String = when {
        includedRoots.size == 1 -> includedRoots.first().displayName
        includedRoots.size > 1 -> JujutsuBundle.message("log.filter.multiple", includedRoots.size)
        excludedRoots.size == 1 -> JujutsuBundle.message("log.filter.root.excluding", excludedRoots.first().displayName)
        excludedRoots.size > 1 -> JujutsuBundle.message("log.filter.root.excluding.multiple", excludedRoots.size)
        else -> JujutsuBundle.message("log.filter.root.all")
    }

    override fun isValueSelected(): Boolean = currentSelection().isActive

    private fun currentSelection() = RootFilterSelection(includedRoots, excludedRoots)

    fun initialize() {
        addChangeListener {
            tableModel.setRootFilter(includedRoots, excludedRoots)
        }
    }

    /** Returns the paths of all currently included roots (defensive copy). */
    fun getSelectedRootPaths(): Set<String> = includedRoots.map { it.directory.path }.toSet()

    /** Returns the paths of all currently excluded roots (defensive copy). */
    fun getExcludedRootPaths(): Set<String> = excludedRoots.map { it.directory.path }.toSet()

    /**
     * Restores a persisted root-filter selection from repository paths.
     * Matches [included]/[excluded] against repos currently in the table model (loaded
     * entries). Should be called from [onDataLoaded] after the model is populated.
     */
    fun setSelectedRoots(included: Set<String>, excluded: Set<String> = emptySet()) {
        includedRoots.clear()
        excludedRoots.clear()
        val allRoots = tableModel.getAllRoots()
        allRoots.filterTo(includedRoots) { it.directory.path in included }
        allRoots.filterTo(excludedRoots) { it.directory.path in excluded }
        notifyFilterChanged()
    }

    /**
     * Check if this filter should be visible.
     * Only show when there are multiple roots.
     */
    fun shouldBeVisible(): Boolean = tableModel.getAllRoots().size > 1

    // Widened from the base class's `protected` so tests can drive the popup's real actions
    // (CycleRootAction, SelectAllRootsAction) directly instead of the persistence-facing API.
    public override fun createActionGroup(): ActionGroup {
        val group = BackgroundActionGroup()

        val roots = tableModel.getAllRoots()
        if (roots.isNotEmpty()) {
            group.add(SelectAllRootsAction(roots))
            group.addSeparator()
        }
        roots.forEach { root ->
            group.add(CycleRootAction(root))
        }

        // Add clear option if roots are selected (consistent with other filters)
        if (isValueSelected()) {
            group.addSeparator()
            group.add(ClearFilterAction())
        }

        return group
    }

    override fun doResetFilter() {
        includedRoots.clear()
        excludedRoots.clear()
        notifyFilterChanged()
    }

    enum class RootState { UNSET, INCLUDED, EXCLUDED }

    /**
     * [Toggleable] is a 2-state marker, but this action has 3 states - implementing it maps
     * "included" to selected so accessibility tooling (which reads
     * [Toggleable.isSelected]/[com.intellij.ui.popup.PopupFactoryImpl.ActionItem]'s
     * checked/unchecked announcement) at least distinguishes the allowlisted state; excluded
     * vs. unset remains conveyed by the icon and [Presentation.getDescription].
     */
    private inner class CycleRootAction(private val root: JujutsuRepository) : AnAction(root.displayName), Toggleable {
        private val color = RepositoryColors.getColor(root)
        private val icons = RootState.entries.associateWith { rootIcon(color, it) }

        init {
            // Reserve space for icon - see PopupFactoryImpl.calcMaxIconSize
            templatePresentation.icon = EmptyIcon.create(JBUI.scale(15))
            // A plain AnAction defaults to KeepPopupOnPerform.Never, which would close the
            // popup after a single click - this action needs repeated clicks to cycle.
            templatePresentation.setKeepPopupOnPerform(KeepPopupOnPerform.Always)
        }

        private fun state(): RootState = when {
            includedRoots.contains(root) -> RootState.INCLUDED
            excludedRoots.contains(root) -> RootState.EXCLUDED
            else -> RootState.UNSET
        }

        override fun actionPerformed(e: AnActionEvent) {
            when (state()) {
                RootState.UNSET -> includedRoots.add(root)
                RootState.INCLUDED -> {
                    includedRoots.remove(root)
                    excludedRoots.add(root)
                }
                RootState.EXCLUDED -> excludedRoots.remove(root)
            }
            notifyFilterChanged()
        }

        override fun update(e: AnActionEvent) {
            val current = state()
            e.presentation.icon = icons.getValue(current)
            e.presentation.description = JujutsuBundle.message(
                when (current) {
                    RootState.UNSET -> "log.filter.root.cycle.toIncluded"
                    RootState.INCLUDED -> "log.filter.root.cycle.toExcluded"
                    RootState.EXCLUDED -> "log.filter.root.cycle.toUnset"
                }
            )
            Toggleable.setSelected(e.presentation, current == RootState.INCLUDED)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }

    /**
     * Header bulk-toggle (jj-idea-qcks): selects every root into [includedRoots] (clearing
     * any exclusions - a root is never in both sets), or clears [includedRoots] back to
     * empty when every root is already included.
     */
    private inner class SelectAllRootsAction(private val roots: List<JujutsuRepository>) : AnAction() {
        init {
            templatePresentation.setKeepPopupOnPerform(KeepPopupOnPerform.Always)
        }

        private fun allIncluded(): Boolean = roots.isNotEmpty() && includedRoots.containsAll(roots)

        override fun actionPerformed(e: AnActionEvent) {
            if (allIncluded()) {
                includedRoots.clear()
            } else {
                includedRoots.clear()
                includedRoots.addAll(roots)
                excludedRoots.clear()
            }
            notifyFilterChanged()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.text = JujutsuBundle.message(
                if (allIncluded()) "log.filter.root.selectNone" else "log.filter.root.selectAll"
            )
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }

    private inner class ClearFilterAction : AnAction(JujutsuBundle.message("log.filter.clear")) {
        override fun actionPerformed(e: AnActionEvent) {
            doResetFilter()
        }
    }
}

private fun rootIcon(color: Color, state: JujutsuRootFilterComponent.RootState): Icon {
    val size = JBUI.scale(10)
    val arc = JBUI.scale(3)
    return object : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            when (state) {
                JujutsuRootFilterComponent.RootState.INCLUDED -> g2.fillRoundRect(x, y, size, size, arc, arc)
                JujutsuRootFilterComponent.RootState.UNSET -> g2.drawRoundRect(x, y, size - 1, size - 1, arc, arc)
                JujutsuRootFilterComponent.RootState.EXCLUDED -> {
                    g2.drawRoundRect(x, y, size - 1, size - 1, arc, arc)
                    g2.drawLine(x, y + size - 1, x + size - 1, y)
                }
            }
            g2.dispose()
        }

        override fun getIconWidth() = size
        override fun getIconHeight() = size
    }
}
