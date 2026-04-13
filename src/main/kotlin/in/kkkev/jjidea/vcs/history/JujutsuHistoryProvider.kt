package `in`.kkkev.jjidea.vcs.history

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsActions
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.annotate.ShowAllAffectedGenericAction
import com.intellij.openapi.vcs.history.VcsAppendableHistorySessionPartner
import com.intellij.openapi.vcs.history.VcsDependentHistoryComponents
import com.intellij.openapi.vcs.history.VcsHistoryProvider
import com.intellij.openapi.vcs.history.VcsHistorySession
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryFor
import javax.swing.JComponent

/**
 * Provides file history for the Jujutsu VCS
 */
class JujutsuHistoryProvider(private val project: Project) : VcsHistoryProvider {
    private val log = Logger.getInstance(javaClass)

    @RequiresBackgroundThread
    override fun createSessionFor(filePath: FilePath): VcsHistorySession? {
        log.info("Creating history session for file: ${filePath.path}")

        try {
            val repo = project.jujutsuRepositoryFor(filePath)

            log.debug("Fetching history for file: $filePath")

            // Use logService to get log entries for this file
            val logService = repo.logService
            val results = logService.getLogAndFileStatuses(Expression.ALL, filePath).getOrThrow()

            log.info("Found ${results.size} revisions for file: ${filePath.path}")

            if (results.isEmpty()) {
                log.warn("No history found for file: ${filePath.path}")
                return null
            }

            // Convert to VcsFileRevision objects
            val revisions = results.map { entry ->
                JujutsuFileRevision(
                    entry.logEntry,
                    filePath,
                    entry.fileChangeStatus,
                    if (entry.logEntry.immutable) repo.gitRemotes else emptyList()
                )
            }

            // Return history session
            val currentRevision = revisions.firstOrNull()?.revisionNumber
            return JujutsuHistorySession(revisions, currentRevision)
        } catch (e: Exception) {
            throw VcsException("Error creating history session: ${e.message}", e)
        }
    }

    override fun reportAppendableHistory(filePath: FilePath, partner: VcsAppendableHistorySessionPartner) {
        log.info("reportAppendableHistory called for file: ${filePath.path}")

        // Report empty session immediately (required by platform)
        val emptySession = JujutsuHistorySession(emptyList(), null)
        partner.reportCreatedEmptySession(emptySession)

        try {
            val session = createSessionFor(filePath)
            session?.revisionList?.forEach { partner.acceptRevision(it) }
        } catch (error: VcsException) {
            log.error("Failed to get file history: ${error.message}")
            partner.reportException(VcsException("Failed to get file history: ${error.message}", error))
        }
    }

    override fun supportsHistoryForDirectories() = false

    override fun getUICustomization(session: VcsHistorySession, root: JComponent) =
        VcsDependentHistoryComponents.createOnlyColumns(arrayOf(CommitterColumnInfo(), CommitTimestampColumnInfo()))

    override fun getAdditionalActions(refresher: Runnable): Array<AnAction> =
        arrayOf(
            ShowAllAffectedGenericAction.getInstance(),
            ActionManager.getInstance().getAction(VcsActions.ACTION_COPY_REVISION_NUMBER)
        )

    override fun isDateOmittable() = false

    override fun getHelpId(): String? = null

    override fun getHistoryDiffHandler() = null

    override fun canShowHistoryFor(virtualFile: VirtualFile) = true
}
