package `in`.kkkev.jjidea.ui.history

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vcs.FilePath
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.CommitTablePanel
import `in`.kkkev.jjidea.ui.DataLoader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads file history data in the background and updates the table model on EDT.
 *
 * Simplified version of JujutsuLogDataLoader for single-file history.
 * No graph building since file history doesn't show the commit graph.
 */
class JujutsuFileHistoryDataLoader(
    private val repo: JujutsuRepository,
    private val filePath: FilePath,
    private val panel: CommitTablePanel<List<LogEntry>>
) : DataLoader {
    private val log = Logger.getInstance(javaClass)

    private val loading = AtomicBoolean(false)
    private val pendingRefresh = AtomicBoolean(false)
    private val currentIndicator = AtomicReference<ProgressIndicator?>(null)

    /**
     * Load file history in the background.
     */
    override fun load() {
        if (!loading.compareAndSet(false, true)) {
            pendingRefresh.set(true)
            return
        }

        currentIndicator.get()?.cancel()

        object : Task.Backgroundable(repo.project, "Loading file history", true) {
            private var entries: List<LogEntry> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                currentIndicator.set(indicator)
                indicator.text = "Loading history for ${filePath.name}..."
                indicator.isIndeterminate = false

                entries = repo.logService.getLog(Expression.ALL, listOf(filePath)).getOrThrow()
                log.info("Loaded ${entries.size} history entries for ${filePath.name}")
            }

            override fun onSuccess() {
                loading.set(false)
                currentIndicator.set(null)

                ApplicationManager.getApplication().invokeLater {
                    panel.onDataLoaded(entries)
                    log.info("Updated with ${entries.size} history entries")
                }

                if (pendingRefresh.compareAndSet(true, false)) {
                    load()
                }
            }

            override fun onThrowable(throwable: Throwable) {
                loading.set(false)
                currentIndicator.set(null)
                log.error("Failed to load file history for ${filePath.name}", throwable)

                if (pendingRefresh.compareAndSet(true, false)) {
                    load()
                }
            }
        }.queue()
    }

    /**
     * Refresh the file history.
     */
    override fun refresh() {
        log.info("Refreshing file history for ${filePath.name}")
        load()
    }
}
