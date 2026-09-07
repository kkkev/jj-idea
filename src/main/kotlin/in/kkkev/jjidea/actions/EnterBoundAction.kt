package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.keymap.KeymapManager
import java.awt.Component
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * Runs the first enabled action bound to the Enter keystroke in the active keymap, using
 * [component] as the context component so the action sees whatever selection/data context that
 * component supplies. Shared by [in.kkkev.jjidea.ui.log.JujutsuLogTable] (default: Show Diff,
 * `Jujutsu.ShowChangesDiff`) and [in.kkkev.jjidea.ui.log.bookmarks.JujutsuBookmarksPanel] (default:
 * navigate to the bookmark's change, `Jujutsu.Bookmark.Navigate`) — both route their
 * double-click handler through this, so rebinding Enter in Keymap settings changes double-click
 * behaviour too (jj-idea-th9h, jj-idea-ib1i).
 *
 * Uses [ActionManager.tryToExecute] — the same entry point a real Enter keypress goes through —
 * rather than hand-building an [com.intellij.openapi.actionSystem.AnActionEvent], so `update()`
 * (including background-thread update actions) and enablement checks run exactly as they would
 * for a real keystroke. Several actions can share the Enter binding (e.g. the log table's Show
 * Diff and the bookmarks panel's Navigate never both apply at once, since each's `update()`
 * disables it outside its own context) — every bound id is tried in turn until one accepts.
 *
 * @return `true` if some action was found bound to Enter (whether or not it was actually enabled
 *   — [ActionManager.tryToExecute] handles that), `false` if nothing is bound to Enter at all.
 */
fun invokeEnterBoundAction(component: Component, actionIds: List<String> = enterBoundActionIds()): Boolean {
    val actionId = actionIds.firstOrNull() ?: return false
    val action: AnAction? = ActionManager.getInstance().getAction(actionId)
    if (action == null) {
        return invokeEnterBoundAction(component, actionIds.drop(1))
    }
    val keyEvent = KeyEvent(component, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, KeyEvent.VK_ENTER, '\r')
    ActionManager.getInstance()
        .tryToExecute(action, keyEvent, component, ActionPlaces.KEYBOARD_SHORTCUT, true)
        .doWhenRejected(Runnable { invokeEnterBoundAction(component, actionIds.drop(1)) })
    return true
}

/** Every action id bound to the plain Enter keystroke in the active keymap. */
fun enterBoundActionIds(): List<String> {
    val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
    return KeymapManager.getInstance().activeKeymap.getActionIds(enter).toList()
}
