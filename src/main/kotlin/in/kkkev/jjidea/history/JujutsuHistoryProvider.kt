package `in`.kkkev.jjidea.history

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.history.*
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import `in`.kkkev.jjidea.JujutsuVcs
import `in`.kkkev.jjidea.ui.JujutsuLogParser
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
            // Create template to extract all needed information
            // Use null byte separator to avoid conflicts with special characters in descriptions
            val template = """
                change_id ++ "\0" ++
                change_id.shortest() ++ "\0" ++
                commit_id ++ "\0" ++
                description ++ "\0" ++
                bookmarks ++ "\0" ++
                parents.map(|c| c.change_id() ++ "~" ++ c.change_id().shortest()).join(", ") ++ "\0" ++
                if(current_working_copy, "true", "false") ++ "\0" ++
                if(conflict, "true", "false") ++ "\0" ++
                if(empty, "true", "false") ++ "\0"
            """.trimIndent().replace("\n", " ")

            log.debug("Executing jj log for file: ${filePath.path}")

            // Get all revisions that modified this file
            val result = vcs.commandExecutor.log(
                revisions = "all()",
                template = template,
                filePaths = listOf(filePath.path)
            )

            if (!result.isSuccess) {
                log.error("Failed to get file history: ${result.stderr}")
                throw VcsException("Failed to get file history: ${result.stderr}")
            }

            log.debug("Parsing log output (${result.stdout.length} bytes)")

            // Parse log entries
            val entries = JujutsuLogParser.parseLog(result.stdout)
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
