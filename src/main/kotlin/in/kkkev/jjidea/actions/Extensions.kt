package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import `in`.kkkev.jjidea.JujutsuBundle
import javax.swing.Icon

/**
 * Look up a registered action by [actionId] and perform it with this event's data context.
 */
fun AnActionEvent.performAction(actionId: String) {
    val action = ActionManager.getInstance().getAction(actionId)!!
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
    val action = ActionManager.getInstance().getAction(actionId)!!
    val event = AnActionEvent.createEvent(action, context, null, place, ActionUiKind.NONE, null)
    @Suppress("DEPRECATION")
    ActionUtil.performActionDumbAwareWithCallbacks(action, event)
}

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
