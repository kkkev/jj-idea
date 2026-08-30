package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.actions.saveDescriptionToHistory
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.newchange.NewChangeDialog

/**
 * "New Change…" — opens [NewChangeDialog] to create a new change based on [targetEntries], with
 * a description, a placement (plain child / `jj new -A` / `jj new -B`), and whether the working
 * copy follows (`--no-edit`) all chosen up front (jj-idea-grc8).
 *
 * Secondary to [in.kkkev.jjidea.actions.change.NewChangeAction] (the quick, no-dialog default)
 * for users who want more control than "plain child, no description".
 */
fun newChangeFromAction(project: Project, repo: JujutsuRepository?, targetEntries: List<LogEntry>) =
    nullAndDumbAwareAction(
        repo,
        (if (targetEntries.size == 1) "log.action.new.from.singular" else "log.action.new.from.plural"),
        JujutsuIcons.NewChange
    ) {
        val dialog = NewChangeDialog(project, target, targetEntries)
        if (!dialog.showAndGet()) return@nullAndDumbAwareAction
        val spec = dialog.result ?: return@nullAndDumbAwareAction

        target.commandExecutor.createCommand {
            new(spec.description, spec.parents, spec.destinationMode, spec.edit)
        }.onSuccess {
            // edit=true (the default) moves the working copy to the new change, so select it;
            // edit=false leaves @ where it was, so just refresh without changing the selection.
            if (spec.edit) {
                target.invalidate(select = WorkingCopy, vfsChanged = true)
            } else {
                target.invalidate(vfsChanged = true)
            }
            project.saveDescriptionToHistory(spec.description)
            log.info("Created new change from ${spec.parents} with description: ${spec.description}")
        }.onFailure { tellUser(project, "log.action.new.error") }
            .executeAsync()
    }
