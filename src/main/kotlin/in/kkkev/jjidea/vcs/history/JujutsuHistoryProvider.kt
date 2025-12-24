package `in`.kkkev.jjidea.vcs.history

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsActions
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.annotate.ShowAllAffectedGenericAction
import com.intellij.openapi.vcs.history.*
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import javax.swing.JComponent

/**
 * Provides file history for the Jujutsu VCS
 */
class JujutsuHistoryProvider(private val vcs: JujutsuVcs) : VcsHistoryProvider {

    private val log = Logger.getInstance(javaClass)

    @RequiresBackgroundThread
    override fun createSessionFor(filePath: FilePath): VcsHistorySession? {
        log.info("Creating history session for file: ${filePath.path}")

        try {
            // Get relative path from repository root
            val relativePath = vcs.getRelativePath(filePath)
            log.debug("Fetching history for file: $relativePath (absolute: ${filePath.path})")

            // Use logService to get log entries for this file
            val result = vcs.logService.getLogBasic(Expression.ALL, listOf(relativePath))

            val entries = result.getOrElse { error ->
                log.error("Failed to get file history: ${error.message}")
                throw VcsException("Failed to get file history: ${error.message}", error)
            }

            log.info("Found ${entries.size} revisions for file: ${filePath.path}")

            if (entries.isEmpty()) {
                log.warn("No history found for file: ${filePath.path}")
                return null
            }

            // Convert to VcsFileRevision objects
            val revisions = entries.map { entry -> JujutsuFileRevision(entry, filePath, vcs) }

            // Return history session
            val currentRevision = revisions.firstOrNull()?.revisionNumber
            return JujutsuHistorySession(revisions, currentRevision)

        } catch (e: Exception) {
            log.error("Error creating history session for ${filePath.path}", e)
            throw VcsException("Error creating history session: ${e.message}", e)
        }
    }

    override fun reportAppendableHistory(filePath: FilePath, partner: VcsAppendableHistorySessionPartner) {
        log.info("reportAppendableHistory called for file: ${filePath.path}")

        // Step 1: Report empty session immediately (required by platform)
        val emptySession = JujutsuHistorySession(emptyList(), null)
        partner.reportCreatedEmptySession(emptySession)

        try {
            // Get relative path from repository root
            val relativePath = vcs.getRelativePath(filePath)
            log.debug("Fetching history for file: $relativePath (absolute: ${filePath.path})")

            // Step 2: Load history
            val result = vcs.logService.getLog(Expression.ALL, listOf(relativePath))

            val entries = result.getOrElse { error ->
                log.error("Failed to get file history: ${error.message}")
                partner.reportException(VcsException("Failed to get file history: ${error.message}", error))
                return
            }

            log.info("Found ${entries.size} revisions for file: ${filePath.path}")

            // Step 3: Stream each revision to the partner
            entries.forEach { entry ->
                val revision = JujutsuFileRevision(entry, filePath, vcs)
                partner.acceptRevision(revision)
            }

        } catch (e: Exception) {
            log.error("Error loading file history for ${filePath.path}", e)
            partner.reportException(VcsException("Error loading file history: ${e.message}", e))
        }

        // Step 4: Return - framework will call partner.finished() automatically
    }

    override fun supportsHistoryForDirectories() = false

    override fun getUICustomization(session: VcsHistorySession, root: JComponent) =
        VcsDependentHistoryComponents.createOnlyColumns(
            arrayOf(CommitterColumnInfo(), CommitTimestampColumnInfo())
        )

    override fun getAdditionalActions(refresher: Runnable): Array<AnAction> {
        return arrayOf(
            ShowAllAffectedGenericAction.getInstance(),
            ActionManager.getInstance().getAction(VcsActions.ACTION_COPY_REVISION_NUMBER)
        )
    }

    override fun isDateOmittable() = false

    override fun getHelpId(): String? = null

    override fun getHistoryDiffHandler(): DiffFromHistoryHandler? = null

    override fun canShowHistoryFor(virtualFile: VirtualFile) = true
}
