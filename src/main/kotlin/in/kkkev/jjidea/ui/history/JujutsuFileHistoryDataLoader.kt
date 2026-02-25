package `in`.kkkev.jjidea.ui.history

import com.intellij.openapi.vcs.FilePath
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.BackgroundDataLoader
import `in`.kkkev.jjidea.ui.common.CommitTablePanel

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
            }
        )
    }

    override fun refresh() {
        log.info("Refreshing file history for ${filePath.name}")
        load()
    }
}
