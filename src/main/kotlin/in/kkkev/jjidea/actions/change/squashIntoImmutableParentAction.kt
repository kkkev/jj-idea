package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.createCommand
import `in`.kkkev.jjidea.jj.immutableWorkingCopyTracker
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons

private val squashImmutableLog = Logger.getInstance("in.kkkev.jjidea.actions.change.squashIntoImmutableParent")

/**
 * "Squash into Immutable Parent" (jj-idea-bzkf, GitHub #143): shown only on a change jj created on
 * top of an immutable working copy, as recorded by
 * [in.kkkev.jjidea.jj.ImmutableWorkingCopyTracker]. Unlike the ordinary squash actions it may
 * target an immutable commit, so it asks for confirmation first.
 */
fun squashIntoImmutableParentAction(project: Project, entry: LogEntry?): AnAction {
    val tracker = project.immutableWorkingCopyTracker
    return nullAndDumbAwareAction(
        entry?.takeIf { tracker.squashTargetFor(it) != null },
        "log.action.squash.into.immutable.parent",
        JujutsuIcons.Squash
    ) {
        tracker.squashTargetFor(target)?.let { squashIntoImmutableParent(project, target.repo, target.id, it) }
    }
}

/**
 * Confirms, then runs `jj squash --from <child> --into <parent> --ignore-immutable -u`. Call on the
 * EDT. jj leaves `@` as a new empty change on top afterwards, its normal state.
 */
fun squashIntoImmutableParent(project: Project, repo: JujutsuRepository, child: ChangeId, parent: LogEntry) {
    val confirmed = Messages.showYesNoDialog(
        project,
        JujutsuBundle.message(
            "log.action.squash.immutable.confirm.message",
            parent.id.short,
            parent.description.summary
        ),
        JujutsuBundle.message("log.action.squash.immutable.confirm.title"),
        Messages.getWarningIcon()
    ) == Messages.YES
    if (!confirmed) return

    repo.createCommand {
        squashInto(listOf(child), parent.id, ignoreImmutable = true, useDestinationMessage = true)
    }
        .onSuccess {
            project.immutableWorkingCopyTracker.forget(repo, child)
            invalidate(select = WorkingCopy, vfsChanged = true)
            squashImmutableLog.info("Squashed $child into immutable ${parent.id}")
        }
        .onFailure { tellUser("log.action.squash.immutable.error") }
        .executeAsync()
}
