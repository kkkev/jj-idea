package `in`.kkkev.jjidea.vcs.diff

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.diff.DiffProvider
import com.intellij.openapi.vcs.diff.ItemLatestState
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.jj.RevisionExpression
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.vcs.changes.JujutsuRevisionNumber
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryFor

/**
 * Provides diff functionality for jujutsu files
 */
class JujutsuDiffProvider(private val project: Project) : DiffProvider {
    private val log = Logger.getInstance(javaClass)

    override fun getLastRevision(file: VirtualFile): ItemLatestState {
        log.debug("Getting last revision for VirtualFile: ${file.path}")

        val filePath = VcsUtil.getFilePath(file)
        val revision = createContentRevision(filePath, WorkingCopy.parent)

        return ItemLatestState(revision.revisionNumber, true, true)
    }

    override fun getLastRevision(filePath: FilePath): ItemLatestState {
        log.debug("Getting last revision for FilePath: ${filePath.path}")

        val revision = createContentRevision(filePath, WorkingCopy.parent)

        return ItemLatestState(revision.revisionNumber, true, true)
    }

    private fun createContentRevision(filePath: FilePath, revision: Revision) =
        project.jujutsuRepositoryFor(filePath).createRevision(filePath, revision)

    override fun createFileContent(revisionNumber: VcsRevisionNumber?, file: VirtualFile) = revisionNumber?.let {
        createContentRevision(VcsUtil.getFilePath(file), RevisionExpression(it.asString()))
    }

    // TODO When addressing jj-idea-3jo, ensure that these return the correct change ids
    override fun getCurrentRevision(file: VirtualFile) = JujutsuRevisionNumber(WorkingCopy)

    override fun getLatestCommittedRevision(file: VirtualFile) = JujutsuRevisionNumber(WorkingCopy.parent)
}
