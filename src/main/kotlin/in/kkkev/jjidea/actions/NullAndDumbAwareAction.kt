package `in`.kkkev.jjidea.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import `in`.kkkev.jjidea.JujutsuBundle
import javax.swing.Icon

/**
 * A [DumbAwareAction] that acts on a list of objects, and is disabled if that list is empty.
 */
abstract class EmptyAndDumbAwareAction<T : Any>(val target: List<T>, messageKey: String, icon: Icon) : DumbAwareAction(
    JujutsuBundle.message(messageKey, *(target as List<Any>).toTypedArray()),
    JujutsuBundle.message("$messageKey.tooltip"),
    icon
) {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !target.isEmpty()
    }

    // Deliberately overridden to call default behaviour to prevent warnings in objects below
    override fun getActionUpdateThread() = super.getActionUpdateThread()
}

data class ActionContext<T>(val target: T, val event: AnActionEvent, val log: Logger)

fun <T : Any> nullAndDumbAwareAction(
    target: T?,
    messageKey: String,
    icon: Icon,
    action: ActionContext<T>.() -> Unit
) = object : EmptyAndDumbAwareAction<T>(listOfNotNull(target), messageKey, icon) {
    private val log = Logger.getInstance(javaClass)

    override fun actionPerformed(e: AnActionEvent) = action(ActionContext(target!!, e, log))
}

fun <T : Any> emptyAndDumbAwareAction(
    target: List<T>,
    messageKey: String,
    icon: Icon,
    action: ActionContext<List<T>>.() -> Unit
) = object : EmptyAndDumbAwareAction<T>(target, messageKey, icon) {
    private val log = Logger.getInstance(javaClass)

    override fun actionPerformed(e: AnActionEvent) = action(ActionContext(target, e, log))
}

/**
 * An action that can extract a "target" from an action, and enable/disable itself according to existence of that
 * target.
 */
abstract class NullAwareAction<T : Any>(
    private val messageKey: String,
    icon: Icon
) : DumbAwareAction(
        JujutsuBundle.message(messageKey),
        JujutsuBundle.message("$messageKey.tooltip"),
        icon
    ) {
    @Volatile
    private var target: T? = null

    abstract fun extractTarget(e: AnActionEvent): T?

    private fun storeTarget(e: AnActionEvent): T? {
        target = extractTarget(e)
        templatePresentation.text = target?.let { JujutsuBundle.message(messageKey, it) }
            ?: JujutsuBundle.message("$messageKey.tooltip")
        return target
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = storeTarget(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        storeTarget(e)?.let(::actionPerformed)
    }

    abstract fun actionPerformed(target: T)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
