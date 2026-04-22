package `in`.kkkev.jjidea.vcs.diff

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.diff.DiffProvider
import com.intellij.openapi.vcs.diff.ItemLatestState
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ContentLocator
import `in`.kkkev.jjidea.jj.MergeParentOf
import `in`.kkkev.jjidea.vcs.changes.JujutsuMergeParentRevisionNumber
import `in`.kkkev.jjidea.vcs.changes.JujutsuRevisionNumber
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryFor

/**
 * Provides diff functionality for jujutsu files
 */
class JujutsuDiffProvider(private val project: Project) : DiffProvider {
    private val log = Logger.getInstance(javaClass)

    override fun getLastRevision(file: VirtualFile): ItemLatestState {
        log.debug("Getting last revision for VirtualFile: ${file.path}")
        return ItemLatestState(revisionNumberFor(VcsUtil.getFilePath(file)), true, true)
    }

    override fun getLastRevision(filePath: FilePath): ItemLatestState {
        log.debug("Getting last revision for FilePath: ${filePath.path}")
        return ItemLatestState(revisionNumberFor(filePath), true, true)
    }

    private fun revisionNumberFor(filePath: FilePath): VcsRevisionNumber {
        val parent = project.jujutsuRepositoryFor(filePath).workingCopy.parentContentLocator
        // TODO Looks like something to pull up to be reused elsewhere?
        return when (parent) {
            is MergeParentOf -> JujutsuMergeParentRevisionNumber(parent.childRevision)
            is ChangeId -> JujutsuRevisionNumber(parent)
            else -> throw VcsException("Cannot find revision number for $parent")
        }
    }

    // TODO Could be simplified further: we now have JujutsuMergeParentRevisionNumber and MergeParentOf as intermediates
    override fun createFileContent(revisionNumber: VcsRevisionNumber?, file: VirtualFile) = revisionNumber?.let {
        val filePath = VcsUtil.getFilePath(file)
        val repo = project.jujutsuRepositoryFor(filePath)
        when (it) {
            // TODO Make these classes share a superclass with contentLocator val
            is JujutsuMergeParentRevisionNumber -> repo.createContentRevision(filePath, it.contentLocator)
            is JujutsuRevisionNumber -> repo.createContentRevision(filePath, it.changeId)
        }
        if (it is JujutsuMergeParentRevisionNumber) {
            repo.createContentRevision(filePath, it.contentLocator)
        } else {
            // TODO Is this right? Test this path
            repo.createContentRevision(filePath, ContentLocator.Empty)
        }
    }

    // TODO When addressing jj-idea-3jo, ensure that these return the correct change ids
    override fun getCurrentRevision(file: VirtualFile) =
        JujutsuRevisionNumber(project.jujutsuRepositoryFor(file).workingCopy.id)

    override fun getLatestCommittedRevision(file: VirtualFile) =
        revisionNumberFor(VcsUtil.getFilePath(file))
}
