package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.ActionButton
import javax.swing.Icon

/**
 * Custom ActionButton that shows visual feedback (different background) when selected.
 * Based on IntelliJ's VCS log implementation.
 *
 * Used in filter toolbars where toggle actions need visual state indication.
 */
class ToggleAwareActionButton(
    action: AnAction,
    presentation: Presentation,
    place: String = "JujutsuFilter"
) : ActionButton(action, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE) {
    init {
        updateIcon()
    }

    override fun getPopState(): Int = if (isSelected) SELECTED else super.getPopState()

    override fun getIcon(): Icon {
        if (isEnabled && isSelected) {
            val selectedIcon = myPresentation.selectedIcon
            if (selectedIcon != null) return selectedIcon
        }
        return super.getIcon()
    }
}
