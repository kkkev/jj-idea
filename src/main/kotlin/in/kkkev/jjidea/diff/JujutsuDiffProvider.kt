package `in`.kkkev.jjidea.diff

import `in`.kkkev.jjidea.JujutsuVcs
import `in`.kkkev.jjidea.changes.JujutsuContentRevision
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.diff.DiffProvider
import com.intellij.openapi.vcs.diff.ItemLatestState
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.changes.JujutsuRevisionNumber

/**
 * Provides diff functionality for jujutsu files
 */
class JujutsuDiffProvider(
    private val project: Project,
    private val vcs: JujutsuVcs
) : DiffProvider {

    private val log = Logger.getInstance(JujutsuDiffProvider::class.java)

    override fun getLastRevision(file: VirtualFile): ItemLatestState? {
        log.debug("Getting last revision for VirtualFile: ${file.path}")

        val filePath = com.intellij.vcsUtil.VcsUtil.getFilePath(file)
        val revision = createRevision(filePath, "@-")

        return ItemLatestState(
            revision?.revisionNumber,
            revision != null,
            true
        )
    }

    override fun getLastRevision(filePath: FilePath): ItemLatestState? {
        log.debug("Getting last revision for FilePath: ${filePath.path}")

        val revision = createRevision(filePath, "@-")

        return ItemLatestState(
            revision?.revisionNumber,
            revision != null,
            true
        )
    }

    override fun createFileContent(
        revisionNumber: VcsRevisionNumber?,
        file: VirtualFile
    ): ContentRevision? {
        if (revisionNumber == null) return null

        val filePath = com.intellij.vcsUtil.VcsUtil.getFilePath(file)
        return createRevision(filePath, revisionNumber.asString())
    }

    override fun getCurrentRevision(file: VirtualFile): VcsRevisionNumber? {
        return JujutsuRevisionNumber("@")
    }

    override fun getLatestCommittedRevision(file: VirtualFile): VcsRevisionNumber? {
        return JujutsuRevisionNumber("@-")
    }

    private fun createRevision(filePath: FilePath, revision: String): ContentRevision? {
        return try {
            JujutsuContentRevision.createRevision(
                filePath,
                revision,
                project,
                vcs
            )
        } catch (e: Exception) {
            log.error("Failed to create revision for ${filePath.path} at $revision", e)
            null
        }
    }
}
