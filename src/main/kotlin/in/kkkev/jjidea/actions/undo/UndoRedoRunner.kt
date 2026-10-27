package `in`.kkkev.jjidea.actions.undo

import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.UNDO_REDO_WINDOW
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.cli.OP_LOG_TEMPLATE
import `in`.kkkev.jjidea.jj.cli.UndoRedoOutcome
import `in`.kkkev.jjidea.jj.cli.UndoRedoPlan
import `in`.kkkev.jjidea.jj.cli.classifyUndoRedoOutcome
import `in`.kkkev.jjidea.jj.cli.parseOpLog
import `in`.kkkev.jjidea.jj.cli.resolveRedo
import `in`.kkkev.jjidea.jj.cli.resolveUndo
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.settings.JujutsuApplicationSettings
import `in`.kkkev.jjidea.ui.components.DateTimeFormatter
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.ui.services.JujutsuUndoService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The IDE's VCS mapping, wherever the project sits inside the repository. If restoring would remove this
 * file from disk the IDE stops treating the project as a jj repo and this plugin (including its Redo
 * button) goes inactive.
 */
internal const val VCS_MAPPING_FILESET = "root-glob:\"**/.idea/vcs.xml\""

private val log = Logger.getInstance("in.kkkev.jjidea.actions.undo.UndoRedoRunner")

/** Paths a `jj diff --summary` from the restore target to now lists as `A`: present now, gone after restoring. */
internal fun removedByRestore(summary: String): List<String> =
    summary.lineSequence().filter { it.startsWith("A ") }.map { it.removePrefix("A ").trim() }.toList()

/** The dedicated warning shown only when [paths] (the VCS mapping) would be removed from disk. */
internal fun vcsMappingRiskMessage(paths: List<String>): String =
    JujutsuBundle.message("undoredo.vcsmapping.message", paths.joinToString(", "))

/** Text of the first-use confirmation: names the operation, its time, and the consequence. */
internal fun undoRedoConfirmMessage(kind: UndoRedoKind, plan: UndoRedoPlan): String = when (plan) {
    is UndoRedoPlan.Named -> {
        val time = DateTimeFormatter.formatAbsolute(plan.labelOp.time)
        if (plan.labelOp.isSnapshot) {
            kind.message("confirm.message.snapshot", time)
        } else {
            kind.message("confirm.message", operationLabel(plan.labelOp), time)
        }
    }

    else -> kind.message("confirm.message.unnamed")
}

/**
 * Asks before the first Undo/Redo (the blind-undo protection the bead requires): `jj undo` acts on
 * the repository's latest operation whoever made it, and rewinds files on disk. "Don't ask again"
 * is remembered only when the user then confirms.
 */
internal fun confirmUndoRedo(
    project: Project,
    kind: UndoRedoKind,
    plan: UndoRedoPlan,
    vcsMappingPaths: List<String> = emptyList()
): Boolean {
    // Never skippable: this one can switch the plugin off, which "Don't ask again" must not hide.
    if (vcsMappingPaths.isNotEmpty()) {
        return MessageDialogBuilder.yesNo(
            JujutsuBundle.message("undoredo.vcsmapping.title"),
            vcsMappingRiskMessage(vcsMappingPaths)
        )
            .yesText(JujutsuBundle.message("undoredo.vcsmapping.button"))
            .noText(Messages.getCancelButton())
            .icon(Messages.getWarningIcon())
            .ask(project)
    }
    // Undoing a plain working-copy snapshot is named in the label and reversed by Redo; only the
    // plugin-disabling case above is worth interrupting for.
    if (plan is UndoRedoPlan.Named && plan.labelOp.isSnapshot) return true
    val state = JujutsuApplicationSettings.getInstance().state
    if (!state.confirmUndoRedo) return true
    val remember = object : DoNotAskOption.Adapter() {
        override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
            if (isSelected && exitCode == Messages.YES) state.confirmUndoRedo = false
        }

        override fun getDoNotShowMessage() = JujutsuBundle.message("undoredo.confirm.remember")
    }
    return MessageDialogBuilder.yesNo(kind.message("confirm.title"), undoRedoConfirmMessage(kind, plan))
        .yesText(kind.message("confirm.button"))
        .noText(Messages.getCancelButton())
        .icon(Messages.getWarningIcon())
        .doNotAsk(remember)
        .ask(project)
}

/**
 * Runs Undo/Redo for [repo] in two phases, so what the user is told is what happens:
 * 1. Take a fresh working-copy snapshot (`jj status`; `executeAsync` also saves open documents
 *    first) and re-read the operation log, then re-resolve the plan from that - not from the cache
 *    the menu text came from, which may be stale.
 * 2. If the plan is available and confirmed, run `jj undo`/`jj redo`, then classify what the log
 *    gained with [classifyUndoRedoOutcome], so the narrow race where edits arrive between the two
 *    phases is reported rather than silently doing nothing.
 */
internal fun runUndoRedo(project: Project, repo: JujutsuRepository, kind: UndoRedoKind) {
    val vcsMappingPaths = AtomicReference<List<String>>(emptyList())
    repo.createCommand {
        val snapshot = status()
        if (snapshot !is CommandResult.Success) return@createCommand snapshot
        val opLog = opLog(UNDO_REDO_WINDOW, OP_LOG_TEMPLATE)
        // Ask jj what this would do to the VCS mapping file (one read-only call, only on click): it is
        // the one file whose removal switches this plugin off.
        if (opLog is CommandResult.Success) {
            val entries = parseOpLog(opLog.stdout)
            val plan = if (kind == UndoRedoKind.UNDO) resolveUndo(entries) else resolveRedo(entries)
            if (plan is UndoRedoPlan.Named) {
                val diff = filesChangedByRestoring(plan.restoresTo, VCS_MAPPING_FILESET)
                if (diff is CommandResult.Success) {
                    vcsMappingPaths.set(removedByRestore(diff.stdout))
                } else {
                    log.warn("Could not check whether ${kind.name} would remove the VCS mapping: ${diff.stderr}")
                }
                log.info(
                    "${kind.name} -> restore to ${plan.restoresTo}: removes VCS mapping = ${vcsMappingPaths.get()}"
                )
            }
        }
        opLog
    }
        .onSuccess { stdout ->
            val entries = parseOpLog(stdout)
            val plan = if (kind == UndoRedoKind.UNDO) resolveUndo(entries) else resolveRedo(entries)
            val head = entries.firstOrNull()
            if (plan is UndoRedoPlan.Unavailable || head == null) {
                notifyUnavailable(project, kind, plan)
            } else if (confirmUndoRedo(project, kind, plan, vcsMappingPaths.get())) {
                execute(project, repo, kind, plan, head)
            }
        }
        .onFailure { tellUser("notification.undo.error") }
        .executeAsync()
}

private fun notifyUnavailable(project: Project, kind: UndoRedoKind, plan: UndoRedoPlan) {
    val reason = if (plan is UndoRedoPlan.Unavailable && plan.reason == UndoRedoPlan.Reason.WORKING_COPY_CHANGED) {
        "disabled.dirty"
    } else {
        "disabled.nothing"
    }
    JujutsuNotifications.notify(
        project,
        JujutsuBundle.message("undoredo.notify.title"),
        kind.message(reason),
        NotificationType.INFORMATION
    )
}

private fun execute(
    project: Project,
    repo: JujutsuRepository,
    kind: UndoRedoKind,
    plan: UndoRedoPlan,
    head: OperationEntry
) {
    val after = AtomicReference<List<OperationEntry>>(emptyList())
    val headMoved = AtomicBoolean(false)
    repo.createCommand {
        // The confirmation dialog may have been open for a while. jj undo deletes anything it finds
        // un-snapshotted from disk (it snapshots first, then undoes that snapshot), so re-check the head
        // immediately before running it and refuse if anything happened since the plan was made.
        val snapshot = status()
        if (snapshot !is CommandResult.Success) return@createCommand snapshot
        val fresh = opLog(UNDO_REDO_WINDOW, OP_LOG_TEMPLATE)
        if (fresh !is CommandResult.Success) return@createCommand fresh
        if (parseOpLog(fresh.stdout).firstOrNull()?.id != head.id) {
            headMoved.set(true)
            return@createCommand fresh
        }
        val result = if (kind == UndoRedoKind.UNDO) undo() else redo()
        if (result is CommandResult.Success) {
            (opLog(UNDO_REDO_WINDOW, OP_LOG_TEMPLATE) as? CommandResult.Success)?.let {
                after.set(parseOpLog(it.stdout))
            }
        }
        result
    }
        .onSuccess {
            invalidate(select = WorkingCopy, vfsChanged = true)
            if (headMoved.get()) {
                JujutsuNotifications.notify(
                    project,
                    JujutsuBundle.message("undoredo.notify.title"),
                    kind.message("notify.changed"),
                    NotificationType.WARNING
                )
                return@onSuccess
            }
            if (kind == UndoRedoKind.UNDO && plan is UndoRedoPlan.Named) {
                // The Stage 1 balloon record may point at the very operation just undone.
                JujutsuUndoService.getInstance(project).clearIfCurrent(this, plan.labelOp.id)
            }
            when (classifyUndoRedoOutcome(after.get(), head.id)) {
                UndoRedoOutcome.DONE -> Unit
                UndoRedoOutcome.SNAPSHOT_ONLY -> JujutsuNotifications.notify(
                    project,
                    JujutsuBundle.message("undoredo.notify.title"),
                    kind.message("notify.snapshotonly"),
                    NotificationType.WARNING
                )

                UndoRedoOutcome.UNEXPECTED -> JujutsuNotifications.notify(
                    project,
                    JujutsuBundle.message("undoredo.notify.title"),
                    kind.message("notify.unexpected"),
                    NotificationType.WARNING
                )
            }
        }
        .onFailure { tellUser("notification.undo.error") }
        .executeAsync()
}
