package `in`.kkkev.jjidea.vcs.diff

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.diff.DiffProvider
import com.intellij.openapi.vcs.diff.ItemLatestState
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.jj.RevisionExpression
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.changes.JujutsuRevisionNumber

/**
 * Provides diff functionality for jujutsu files
 */
class JujutsuDiffProvider(private val vcs: JujutsuVcs) : DiffProvider {
    private val log = Logger.getInstance(javaClass)

    override fun getLastRevision(file: VirtualFile): ItemLatestState? {
        log.debug("Getting last revision for VirtualFile: ${file.path}")

        val filePath = VcsUtil.getFilePath(file)
        val revision = vcs.createRevision(filePath, WorkingCopy.parent)

        return ItemLatestState(revision.revisionNumber, true, true)
    }

    override fun getLastRevision(filePath: FilePath): ItemLatestState? {
        log.debug("Getting last revision for FilePath: ${filePath.path}")

        val revision = vcs.createRevision(filePath, WorkingCopy.parent)

        return ItemLatestState(revision.revisionNumber, true, true)
    }

    override fun createFileContent(revisionNumber: VcsRevisionNumber?, file: VirtualFile) = revisionNumber?.let {
        vcs.createRevision(VcsUtil.getFilePath(file), RevisionExpression(it.toString()))
    }

    // TODO When addressing jj-idea-3jo, ensure that these return the correct change ids
    override fun getCurrentRevision(file: VirtualFile) = JujutsuRevisionNumber(WorkingCopy)

    override fun getLatestCommittedRevision(file: VirtualFile) = JujutsuRevisionNumber(WorkingCopy.parent)
}