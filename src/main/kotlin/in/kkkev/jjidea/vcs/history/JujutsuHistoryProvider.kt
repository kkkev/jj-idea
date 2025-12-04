package `in`.kkkev.jjidea.vcs.history

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.history.*
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import javax.swing.JComponent

/**
 * Provides file history for the Jujutsu VCS
 */
class JujutsuHistoryProvider(private val vcs: JujutsuVcs) : VcsHistoryProvider {

    private val log = Logger.getInstance(JujutsuHistoryProvider::class.java)

    @RequiresBackgroundThread
    override fun createSessionFor(filePath: FilePath): VcsHistorySession? {
        log.info("Creating history session for file: ${filePath.path}")

        try {
            log.debug("Fetching history for file: ${filePath.path}")

            // Use logService to get log entries for this file
            val result = vcs.logService.getLogBasic(
                revisions = "all()",
                filePaths = listOf(filePath.path)
            )

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
            val revisions: List<VcsFileRevision> = entries.map { entry ->
                JujutsuFileRevision(entry, filePath, vcs)
            }

            // Return history session
            return JujutsuHistorySession(revisions, filePath)

        } catch (e: Exception) {
            log.error("Error creating history session for ${filePath.path}", e)
            throw VcsException("Error creating history session: ${e.message}", e)
        }
    }

    override fun reportAppendableHistory(filePath: FilePath, partner: VcsAppendableHistorySessionPartner) {
        // Not supported - we load all history at once
    }

    override fun supportsHistoryForDirectories() = false

    override fun getUICustomization(session: VcsHistorySession, root: JComponent): VcsDependentHistoryComponents {
        return VcsDependentHistoryComponents(null, null, null)
    }

    override fun getAdditionalActions(refresher: Runnable): Array<AnAction> = emptyArray()

    override fun isDateOmittable() = true // JJ doesn't track timestamps by default

    override fun getHelpId(): String? = null

    override fun getHistoryDiffHandler(): DiffFromHistoryHandler? = null

    override fun canShowHistoryFor(virtualFile: VirtualFile) = true
}
