package `in`.kkkev.jjidea.vcs.history

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.VcsLogFileHistoryHandler
import com.intellij.vcs.log.VcsLogFileHistoryHandler.Rename
import com.intellij.vcs.log.VcsLogFilterCollection
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.jujutsuRoot

/**
 * Modern file history handler for VCS Log integration.
 * Provides better UI with toolbar at top and details pane on right.
 */
// TODO Can this constructor tolerate a lack of project?
class JujutsuLogFileHistoryHandler(project: Project) : VcsLogFileHistoryHandler {
    private val log = Logger.getInstance(javaClass)

    override val supportedVcs get() = JujutsuVcs.getKey()

    override fun getSupportedFilters(
        root: VirtualFile,
        filePath: FilePath,
        hash: Hash?
    ): Set<VcsLogFilterCollection.FilterKey<*>> {
        // JJ doesn't support filters yet (branch, revision filters would go here)
        // TODO Fill this in
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
        val jujutsuRoot = root.jujutsuRoot

        val relativePath = jujutsuRoot.getRelativePath(filePath)

        // Use Expression.ALL for now (could support hash-based starting point later)
        val logService = jujutsuRoot.logService
        val result: Result<List<LogEntry>> = logService.getLog(Expression.ALL, listOf(relativePath))

        result.fold(
            onSuccess = { entries: List<LogEntry> ->
                // Limit to requested count for fast loading
                val limited = if (commitCount > 0) entries.take(commitCount) else entries
                log.info("Fast history loaded ${limited.size} entries for ${filePath.path}")

                limited.forEach { entry: LogEntry ->
                    consumer(JujutsuFileRevision(entry, filePath, jujutsuRoot))
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
        val jujutsuRoot = root.jujutsuRoot

        val relativePath = jujutsuRoot.getRelativePath(filePath)

        // Use Expression.ALL for full history
        val logService = jujutsuRoot.logService
        val result: Result<List<LogEntry>> = logService.getLog(Expression.ALL, listOf(relativePath))

        result.fold(
            onSuccess = { entries: List<LogEntry> ->
                log.info("Collected ${entries.size} history entries for ${filePath.path}")

                entries.forEach { entry: LogEntry ->
                    consumer(JujutsuFileRevision(entry, filePath, jujutsuRoot))
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
        // TODO: Implement rename detection for file history (lower priority)
        return null
    }
}
