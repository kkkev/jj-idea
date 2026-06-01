package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.actions.nullAndDumbAwareAction
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.loadRepoEntries
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.squash.SquashIntoDialog
import `in`.kkkev.jjidea.ui.squash.SquashMode
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater

/**
 * Squash From action. Opens a dialog with the right-clicked change fixed as the destination,
 * letting the user pick one or more source changes to fold in via `jj squash --from ... --into ...`.
 *
 * This is the inverse of [squashIntoAction]: destination is pre-selected, sources are chosen.
 * The working copy is pre-selected as a source when it is mutable.
 *
 * The destination must be mutable.
 */
fun squashFromAction(
    project: Project,
    destination: LogEntry?
) = nullAndDumbAwareAction(destination, "log.action.squash.from", JujutsuIcons.Squash) {
    runLater {
        val dialog = SquashIntoDialog(project, target.repo, SquashMode.PickSources(target), emptyList())
        if (!dialog.showAndGet()) return@runLater
        val spec = dialog.result ?: return@runLater
        val specSourceIds = spec.sources.toSet()
        runInBackground {
            val sourceEntries = loadRepoEntries(project, target.repo).filter { it.id in specSourceIds }
            executeSquashInto(project, target.repo, sourceEntries, spec)
        }
    }
}
