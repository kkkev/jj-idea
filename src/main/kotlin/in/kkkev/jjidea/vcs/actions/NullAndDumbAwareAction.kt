package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import javax.swing.Icon

/**
 * A [DumbAwareAction] that acts on a list of objects, and is disabled if that list is empty.
 */
abstract class EmptyAndDumbAwareAction<T>(val target: List<T>, messageKey: String, icon: Icon) : DumbAwareAction(
    JujutsuBundle.message(messageKey),
    JujutsuBundle.message("${messageKey}.tooltip"),
    icon
) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !target.isEmpty()
    }

    // Deliberately overridden to call default behaviour to prevent warnings in objects below
    override fun getActionUpdateThread() = super.getActionUpdateThread()
}

data class ActionContext<T>(val target: T, val event: AnActionEvent, val log: Logger)

fun <T> nullAndDumbAwareAction(target: T?, messageKey: String, icon: Icon, action: ActionContext<T>.() -> Unit) =
    object : EmptyAndDumbAwareAction<T>(listOfNotNull(target), messageKey, icon) {
        private val log = Logger.getInstance(javaClass)
        override fun actionPerformed(e: AnActionEvent) = action(ActionContext(target!!, e, log))
    }

fun <T> emptyAndDumbAwareAction(
    target: List<T>,
    messageKey: String,
    icon: Icon,
    action: ActionContext<List<T>>.() -> Unit
) =
    object : EmptyAndDumbAwareAction<T>(target, messageKey, icon) {
        private val log = Logger.getInstance(javaClass)
        override fun actionPerformed(e: AnActionEvent) = action(ActionContext(target, e, log))
    }