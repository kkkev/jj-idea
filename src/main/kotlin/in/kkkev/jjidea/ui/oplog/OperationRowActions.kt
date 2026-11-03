package `in`.kkkev.jjidea.ui.oplog

import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.ui.components.DateTimeFormatter

/** Availability of a row action: [disabledReason] is shown as the description when not [enabled]. */
data class RowActionState(val enabled: Boolean, val disabledReason: String? = null)

/**
 * Pure availability rules for the two per-row actions, extracted so they can be tested without
 * the platform. [head] is the repository's current head operation (restoring to it is a no-op).
 * Both are disabled, with a reason, on the root operation: it has no effect to revert, and
 * "restoring" to the empty repo is not something this UI offers.
 */
internal fun revertState(entry: OperationEntry?): RowActionState = when {
    entry == null -> RowActionState(false, JujutsuBundle.message("oplog.action.noselection"))
    entry.isRoot -> RowActionState(false, JujutsuBundle.message("oplog.action.root"))
    else -> RowActionState(true)
}

internal fun restoreState(entry: OperationEntry?, head: OperationEntry?): RowActionState = when {
    entry == null -> RowActionState(false, JujutsuBundle.message("oplog.action.noselection"))
    entry.isRoot -> RowActionState(false, JujutsuBundle.message("oplog.action.root"))
    entry.id == head?.id -> RowActionState(false, JujutsuBundle.message("oplog.action.restore.current"))
    else -> RowActionState(true)
}

/** Copy availability: needs a selection and non-blank [text]; [emptyReason] explains a blank one. */
internal fun copyState(entry: OperationEntry?, text: String?, emptyReason: String): RowActionState = when {
    entry == null -> RowActionState(false, JujutsuBundle.message("oplog.action.noselection"))
    text.isNullOrBlank() -> RowActionState(false, emptyReason)
    else -> RowActionState(true)
}

/**
 * The row "Go to Current Operation" lands on: the newest shown row of the selected row's repository, else of the only
 * repository shown, else the top row (several repos, nothing selected).
 */
internal fun headRow(shown: List<OperationRow>, selected: OperationRow?): OperationRow? {
    val repo = selected?.repo ?: shown.map { it.repo }.distinct().singleOrNull() ?: return shown.firstOrNull()
    return shown.firstOrNull { it.repo == repo }
}

internal fun goToHeadState(shown: List<OperationRow>, selected: OperationRow?): RowActionState {
    val target = headRow(shown, selected)
    return when {
        target == null -> RowActionState(false, JujutsuBundle.message("oplog.empty"))
        target == selected -> RowActionState(false, JujutsuBundle.message("oplog.action.gotohead.already"))
        else -> RowActionState(true)
    }
}

/** The operation's command line as shown in the Command column (jj's `args: ` prefix removed). */
internal fun OperationEntry.command(): String = args.removePrefix("args: ")

/** First line of the description; the root operation (empty description) gets a placeholder. */
internal fun OperationEntry.title(): String = description.lineSequence().firstOrNull().orEmpty().ifBlank {
    if (isRoot) JujutsuBundle.message("oplog.root.description") else JujutsuBundle.message("oplog.no.description")
}

/** Confirmation text for Restore: names the target and the consequence (later operations' effects are discarded). */
internal fun restoreConfirmMessage(entry: OperationEntry, laterCount: Int): String =
    JujutsuBundle.message(
        "oplog.restore.confirm.message",
        entry.title(),
        DateTimeFormatter.formatAbsolute(entry.time),
        laterCount
    )
