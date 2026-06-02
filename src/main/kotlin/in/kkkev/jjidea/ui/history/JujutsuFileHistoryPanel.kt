package `in`.kkkev.jjidea.ui.history

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.common.CommitTablePanel

/**
 * Panel for displaying file history with the same styling as the custom log view.
 *
 * Reuses components from the log panel:
 * - JujutsuLogTable
 * - JujutsuLogTableModel
 * - JujutsuCommitDetailsPanel
 * - All standard renderers
 *
 * Simplified toolbar: refresh, search (no paths/root filters since this is single-file).
 */
class JujutsuFileHistoryPanel(project: Project, private val filePath: FilePath, private val repo: JujutsuRepository) :
    CommitTablePanel<List<LogEntry>>(
        project,
        "JujutsuFileHistoryToolbar",
        { JujutsuFileHistoryDataLoader(repo, filePath, it) }
    ) {
    private val log = Logger.getInstance(javaClass)

    init {
        log.info("JujutsuFileHistoryPanel initialized for file: ${filePath.name}")
    }

    override fun onDataLoaded(newData: List<LogEntry>) {
        logTable.setEntries(newData)
    }

    override fun updateTableStuff() {
        updateColumnVisibility()
        logTable.repaint()
    }

    override fun dispose() {
        log.info("JujutsuFileHistoryPanel disposed")
        detailsPanel.dispose()
    }
}
