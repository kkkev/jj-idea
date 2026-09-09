package `in`.kkkev.jjidea.ui.history

import com.intellij.openapi.vcs.FilePath
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RepositoryHealth
import `in`.kkkev.jjidea.jj.classifyRepositoryFailure
import `in`.kkkev.jjidea.ui.common.BackgroundDataLoader
import `in`.kkkev.jjidea.ui.common.CommitTablePanel
import `in`.kkkev.jjidea.ui.services.JujutsuNotifications

/**
 * Loads file history data in the background and updates the table model on EDT.
 *
 * Simplified version of UnifiedJujutsuLogDataLoader for single-file history.
 * No graph building since file history doesn't show the commit graph.
 */
class JujutsuFileHistoryDataLoader(
    private val repo: JujutsuRepository,
    private val filePath: FilePath,
    private val panel: CommitTablePanel<List<LogEntry>>
) : BackgroundDataLoader(repo.project, "Loading file history") {
    override fun load() {
        var entries: List<LogEntry> = emptyList()

        executeInBackground(
            run = { indicator ->
                indicator.text = "Loading history for ${filePath.name}..."
                indicator.isIndeterminate = false

                entries = repo.logService.getLog(Expression.ALL, listOf(filePath)).getOrThrow()
                log.info("Loaded ${entries.size} history entries for ${filePath.name}")
            },
            onSuccess = {
                panel.onDataLoaded(entries)
                log.info("Updated with ${entries.size} history entries")
            },
            onError = { e ->
                // jj-idea-27b4: a stale workspace used to fall through to the default onError
                // (a bare log.warn), leaving the history tab silently empty. Offer the same
                // remedy every other action does, with retry re-running this load.
                val health = classifyRepositoryFailure(e.message.orEmpty())
                if (health is RepositoryHealth.Stale) {
                    JujutsuNotifications.notifyWorkingCopyUnavailable(repo.project, repo, health) { load() }
                } else {
                    log.warn("Background task failed: ${e.message}", e)
                }
            }
        )
    }

    override fun refresh() {
        log.info("Refreshing file history for ${filePath.name}")
        load()
    }
}
