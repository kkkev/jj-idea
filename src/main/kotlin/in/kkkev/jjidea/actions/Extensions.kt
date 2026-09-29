package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.bookmark.pushBookmarkAction
import javax.swing.Icon

object ManagedActions {
    private val actionManager by lazy { ActionManager.getInstance() }

    /**
     * Looks up a registered action by id, for use in this panel's own menus in place of the
     * fixed-target factories in [in.kkkev.jjidea.actions.bookmark] - the *same instance* the
     * Keymap settings page resolves a shortcut for, so it can show a hint here too (jj-idea-ib1i),
     * exactly as [in.kkkev.jjidea.ui.log.JujutsuLogContextMenuActions.createActionGroup]'s
     * `liveSelection` path already does for New Change/Edit/Rebase. Push stays on the
     * fixed-target [pushBookmarkAction] submenu below - it's shared with call sites (the bookmark
     * widget, the log's chip submenu) that never publish [JujutsuDataKeys.BOOKMARK_TARGET], so
     * swapping it there would make Push silently disable itself in every *other* context instead.
     */
    operator fun get(key: String): AnAction? = actionManager.getAction(key)

    fun getId(action: AnAction) = actionManager.getId(action)
}

/**
 * Look up a registered action by [actionId] and perform it with this event's data context.
 */
fun AnActionEvent.performAction(actionId: String) {
    val action = ManagedActions[actionId]!!
    @Suppress("DEPRECATION")
    ActionUtil.performActionDumbAwareWithCallbacks(action, this)
}

/**
 * Look up a registered action by [actionId] and perform it with a custom [DataContext].
 */
fun performAction(
    actionId: String,
    context: DataContext,
    place: String = ActionPlaces.UNKNOWN
) {
    val action = ManagedActions[actionId]!!
    val event = AnActionEvent.createEvent(action, context, null, place, ActionUiKind.NONE, null)
    @Suppress("DEPRECATION")
    ActionUtil.performActionDumbAwareWithCallbacks(action, event)
}

val AnAction.id get() = ManagedActions.getId(this)

fun DefaultActionGroup.add(action: AnAction?) = action?.let { add(it) }

fun DefaultActionGroup.addPopup(resourceKeyPrefix: String, icon: Icon, builder: DefaultActionGroup.() -> Unit) = add(
    DefaultActionGroup(
        JujutsuBundle.message(resourceKeyPrefix),
        JujutsuBundle.message("$resourceKeyPrefix.description"),
        icon
    ).apply {
        isPopup = true
        builder()
    }
)
