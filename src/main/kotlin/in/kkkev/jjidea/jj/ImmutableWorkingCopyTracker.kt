package `in`.kkkev.jjidea.jj

import com.intellij.notification.Notification
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.actions.change.squashIntoImmutableParent
import `in`.kkkev.jjidea.jj.cli.OP_LOG_TEMPLATE
import `in`.kkkev.jjidea.jj.cli.parseOpLog
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryForRoot
import java.util.concurrent.ConcurrentHashMap

/**
 * The stderr line jj prints when a snapshot finds the working-copy commit immutable: it leaves the
 * commit alone, puts the edits in a new child and moves `@` there. Verified on jj 0.44 and 0.46.
 * Matched as a substring so a wording tweak around it ("Warning: ...; a new commit has been
 * created on top of it.") still matches. GitHub #143, jj-idea-bzkf.
 */
internal const val IMMUTABLE_WORKING_COPY_WARNING = "working-copy commit is immutable"

internal fun immutableWorkingCopyMoved(stderr: String) = IMMUTABLE_WORKING_COPY_WARNING in stderr

/**
 * Whether [new] is the child jj created on top of an immutable [previous] working copy. Used on the
 * repo-state path, which catches snapshots the plugin didn't run itself (e.g. `jj status` in a
 * terminal): [previous] was immutable, [new] is its child, and a snapshot operation ran in between.
 * A `jj new` also yields a child of an immutable `@`, but never a snapshot op - so it, and jj >= 0.45
 * eagerly replacing a *mutable* `@` that just became immutable, are both excluded.
 *
 * [opsSincePrevious] is every operation at or after the moment [previous] was loaded, not only the
 * newest, so another operation landing after the snapshot doesn't hide it.
 */
internal fun isAutoCreatedChild(previous: LogEntry?, new: LogEntry, opsSincePrevious: List<OperationEntry>) =
    previous != null &&
        previous.immutable &&
        previous.id != new.id &&
        previous.id in new.parentIds &&
        opsSincePrevious.any { it.isSnapshot }

val Project.immutableWorkingCopyTracker: ImmutableWorkingCopyTracker get() = service()

/**
 * Remembers children jj auto-created on top of an immutable working copy (GitHub #143) so the user
 * can squash them back into the immutable commit when done editing it: a sticky notification when
 * it happens, plus a log context-menu action ([squashTargetFor]) that outlives the notification.
 *
 * Detected two ways, deduplicated on the child's change id: [onStderrWarning] (exact, covers
 * snapshots the plugin ran) and [onWorkingCopiesChanged] (covers terminal snapshots).
 *
 * Scale: O(1) per jj invocation / working-copy change; at most one `op log` (20 entries) and one
 * `@ | @-` read per detected event.
 */
@Service(Service.Level.PROJECT)
class ImmutableWorkingCopyTracker(private val project: Project) {
    private val log = Logger.getInstance(javaClass)

    private class Recorded(val repo: JujutsuRepository, val childId: ChangeId, val parent: LogEntry) {
        @Volatile
        var notification: Notification? = null
    }

    private val recorded = ConcurrentHashMap<String, Recorded>()
    private val lastSeen = ConcurrentHashMap<String, Pair<LogEntry, Long>>()
    private val stderrInFlight = ConcurrentHashMap.newKeySet<String>()

    private fun key(repoPath: String, child: ChangeId) = "$repoPath\u0000${child.full}"

    /** The immutable commit [entry] was auto-created on top of, or null if [entry] isn't such a child. */
    fun squashTargetFor(entry: LogEntry): LogEntry? = recorded[key(entry.repo.directory.path, entry.id)]?.parent

    /** Stderr path: a jj run in [directory] reported the immutable-working-copy warning. */
    fun onStderrWarning(directory: VirtualFile) {
        val repo = project.jujutsuRepositoryForRoot(directory) ?: return
        if (!stderrInFlight.add(directory.path)) return
        runInBackground {
            try {
                if (project.isDisposed) return@runInBackground
                val entries = repo.logService.getLogBasic(Expression("@ | @-")).getOrNull().orEmpty()
                val child = entries.firstOrNull { it.isWorkingCopy } ?: return@runInBackground
                val parent = entries.firstOrNull { it.immutable && it.id in child.parentIds }
                // No immutable parent means a later operation already moved @; the state path
                // (onWorkingCopiesChanged) takes it from there.
                if (parent != null) record(repo, child.id, parent)
            } finally {
                stderrInFlight.remove(directory.path)
            }
        }
    }

    /** State path: called on every change of the loaded working copies, keyed by repo path. */
    fun onWorkingCopiesChanged(new: Map<String, LogEntry>) {
        val now = System.currentTimeMillis()
        for ((path, entry) in new) {
            val previous = lastSeen.put(path, entry to now)
            val previousEntry = previous?.first
            val maybeAutoChild = previousEntry != null &&
                previousEntry.immutable &&
                previousEntry.id != entry.id &&
                previousEntry.id in entry.parentIds
            val hasRecorded = recorded.values.any { it.repo.directory.path == path && it.childId != entry.id }
            if (!maybeAutoChild && !hasRecorded) continue
            runInBackground {
                if (project.isDisposed) return@runInBackground
                if (maybeAutoChild) checkSnapshotSince(entry.repo, previousEntry, previous.second, entry)
                if (hasRecorded) forgetVanished(entry.repo, entry.id)
            }
        }
    }

    private fun checkSnapshotSince(repo: JujutsuRepository, previous: LogEntry, sinceMillis: Long, new: LogEntry) {
        val result = repo.commandExecutor.opLog(OP_LOG_WINDOW, OP_LOG_TEMPLATE)
        if (result !is CommandExecutor.CommandResult.Success) return
        // jj's op times have whole-second resolution, so compare at that granularity.
        val floorSeconds = sinceMillis / 1000
        val ops = parseOpLog(result.stdout).filter { it.time.epochSeconds >= floorSeconds }
        if (isAutoCreatedChild(previous, new, ops)) record(repo, new.id, previous)
    }

    private fun forgetVanished(repo: JujutsuRepository, currentId: ChangeId) {
        recorded.values
            .filter { it.repo.directory.path == repo.directory.path && it.childId != currentId }
            .forEach { r ->
                val found = repo.logService.getLogBasic(Expression(r.childId.full)).getOrNull()
                if (found.isNullOrEmpty()) forget(repo, r.childId)
            }
    }

    internal fun record(repo: JujutsuRepository, child: ChangeId, parent: LogEntry): Boolean {
        val r = Recorded(repo, child, parent)
        if (recorded.putIfAbsent(key(repo.directory.path, child), r) != null) return false
        log.info("jj left immutable ${parent.id} unchanged and moved edits to child $child in ${repo.directory.path}")
        r.notification = JujutsuNotifications.notifyImmutableWorkingCopyMoved(project, repo, parent) {
            squashIntoImmutableParent(project, repo, child, parent)
        }
        return true
    }

    /** Drops the record for [child] (squashed back, or gone) and expires its notification. */
    fun forget(repo: JujutsuRepository, child: ChangeId) {
        recorded.remove(key(repo.directory.path, child))?.notification?.expire()
    }

    private companion object {
        const val OP_LOG_WINDOW = 20
    }
}
