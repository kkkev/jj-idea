package `in`.kkkev.jjidea.vcs.history

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.VcsLogFileHistoryHandler
import com.intellij.vcs.log.VcsLogFileHistoryHandler.Rename
import com.intellij.vcs.log.VcsLogFilterCollection
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.JujutsuVcs

/**
 * Modern file history handler for VCS Log integration.
 * Provides better UI with toolbar at top and details pane on right.
 */
class JujutsuLogFileHistoryHandler(private val project: Project) : VcsLogFileHistoryHandler {

    private val log = Logger.getInstance(javaClass)

    override val supportedVcs: VcsKey
        get() = JujutsuVcs.getKey()

    override fun getSupportedFilters(root: VirtualFile, filePath: FilePath, hash: Hash?): Set<VcsLogFilterCollection.FilterKey<*>> {
        // JJ doesn't support filters yet (branch, revision filters would go here)
        return emptySet()
    }

    @Throws(VcsException::class)
    override fun getHistoryFast(
        root: VirtualFile,
        filePath: FilePath,
        hash: Hash?,
        filters: VcsLogFilterCollection,
        commitCount: Int,
        consumer: (VcsFileRevision) -> Unit
    ) {
        log.info("Loading fast file history for ${filePath.path} (limit: $commitCount)")

        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        val vcs = vcsManager.getVcsFor(root) as? JujutsuVcs
            ?: throw VcsException("Jujutsu VCS not found for root: $root")

        val relativePath = vcs.getRelativePath(filePath)

        // Use Expression.ALL for now (could support hash-based starting point later)
        val result: Result<List<LogEntry>> = vcs.logService.getLog(Expression.ALL, listOf(relativePath))

        result.fold(
            onSuccess = { entries: List<LogEntry> ->
                // Limit to requested count for fast loading
                val limited = if (commitCount > 0) entries.take(commitCount) else entries
                log.info("Fast history loaded ${limited.size} entries for ${filePath.path}")

                limited.forEach { entry: LogEntry ->
                    consumer(JujutsuFileRevision(entry, filePath, vcs))
                }
            },
            onFailure = { error: Throwable ->
                log.error("Failed to load fast file history for ${filePath.path}", error)
                throw VcsException("Failed to load file history: ${error.message}", error)
            }
        )
    }

    @Throws(VcsException::class)
    override fun collectHistory(
        root: VirtualFile,
        filePath: FilePath,
        hash: Hash?,
        filters: VcsLogFilterCollection,
        consumer: (VcsFileRevision) -> Unit
    ) {
        log.info("Collecting full file history for ${filePath.path}")

        val vcsManager = ProjectLevelVcsManager.getInstance(project)
        val vcs = vcsManager.getVcsFor(root) as? JujutsuVcs
            ?: throw VcsException("Jujutsu VCS not found for root: $root")

        val relativePath = vcs.getRelativePath(filePath)

        // Use Expression.ALL for full history
        val result: Result<List<LogEntry>> = vcs.logService.getLog(Expression.ALL, listOf(relativePath))

        result.fold(
            onSuccess = { entries: List<LogEntry> ->
                log.info("Collected ${entries.size} history entries for ${filePath.path}")

                entries.forEach { entry: LogEntry ->
                    consumer(JujutsuFileRevision(entry, filePath, vcs))
                }
            },
            onFailure = { error: Throwable ->
                log.error("Failed to collect file history for ${filePath.path}", error)
                throw VcsException("Failed to collect file history: ${error.message}", error)
            }
        )
    }

    @Throws(VcsException::class)
    override fun getRename(
        root: VirtualFile,
        filePath: FilePath,
        beforeHash: Hash,
        afterHash: Hash
    ): Rename? {
        // JJ doesn't explicitly track file renames yet
        // TODO: Implement when JJ adds rename tracking or when we add heuristic detection
        log.debug("Rename detection not yet supported for Jujutsu")
        return null
    }
}
