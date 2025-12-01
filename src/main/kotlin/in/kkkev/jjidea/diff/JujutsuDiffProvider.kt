package `in`.kkkev.jjidea.diff

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.diff.DiffProvider
import com.intellij.openapi.vcs.diff.ItemLatestState
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.JujutsuVcs
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

        val filePath = VcsUtil.getFilePath(file)
        val revision = vcs.createRevision(filePath, "@-")

        return ItemLatestState(revision.revisionNumber, true, true)
    }

    override fun getLastRevision(filePath: FilePath): ItemLatestState? {
        log.debug("Getting last revision for FilePath: ${filePath.path}")

        val revision = vcs.createRevision(filePath, "@-")

        return ItemLatestState(revision.revisionNumber, true, true)
    }

    override fun createFileContent(
        revisionNumber: VcsRevisionNumber?,
        file: VirtualFile
    ): ContentRevision? {
        if (revisionNumber == null) return null

        val filePath = VcsUtil.getFilePath(file)
        return vcs.createRevision(filePath, revisionNumber.asString())
    }

    override fun getCurrentRevision(file: VirtualFile) = JujutsuRevisionNumber("@")

    override fun getLatestCommittedRevision(file: VirtualFile) = JujutsuRevisionNumber("@-")
}
