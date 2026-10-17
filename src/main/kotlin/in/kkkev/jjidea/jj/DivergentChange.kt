package `in`.kkkev.jjidea.jj

import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle

/**
 * Divergent-change handling for writes (jj-idea-wiz0, GH #130). Reads address commits by commit id and
 * so can't hit this; writes address the change id, which jj refuses once the change has become divergent.
 * All detail lives here so write paths need no per-action handling - see
 * [CommandExecutor.CommandResult.Failure.Divergent].
 */
private val DIVERGENT_ERROR = Regex("""Change ID `([^`]+)` is divergent""")

/**
 * The divergent change id jj complains about, or null if this failure isn't that error. Matches exit 1 plus the
 * error line only (identical at jj 0.37 and 0.44), not the Hint lines.
 */
internal fun divergentChangeId(exitCode: Int, stderr: String): String? =
    if (exitCode == 1) DIVERGENT_ERROR.find(stderr)?.groupValues?.get(1) else null

/** Call on the EDT. Refreshes the log so the user can see the divergent versions, and says so. Never retries the write. */
fun JujutsuRepository.handleDivergentChange(failure: CommandExecutor.CommandResult.Failure.Divergent) {
    invalidate()
    Messages.showWarningDialog(
        project,
        JujutsuBundle.message("divergent.change.message", failure.changeId),
        JujutsuBundle.message("divergent.change.title")
    )
}

/** Like [CommandExecutor.CommandResult.tellUser], but a divergent failure gets its refresh prompt instead. */
fun CommandExecutor.CommandResult.tellUser(repo: JujutsuRepository, resourceKeyPrefix: String) =
    if (this is CommandExecutor.CommandResult.Failure.Divergent) {
        repo.handleDivergentChange(this)
    } else {
        tellUser(repo.project, resourceKeyPrefix)
    }
