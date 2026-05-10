package `in`.kkkev.jjidea.vcs.diff

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.diff.DiffProvider
import com.intellij.openapi.vcs.diff.ItemLatestState
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import `in`.kkkev.jjidea.vcs.changes.contentLocator
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryFor

/**
 * Provides diff functionality for jujutsu files
 */
class JujutsuDiffProvider(private val project: Project) : DiffProvider {
    private val log = Logger.getInstance(javaClass)

    override fun getLastRevision(file: VirtualFile) = getLastRevision(file.filePath)

    override fun getLastRevision(filePath: FilePath): ItemLatestState {
        log.debug("Getting last revision for FilePath: ${filePath.path}")
        return ItemLatestState(project.jujutsuRepositoryFor(filePath).revisionNumberFor(filePath), true, true)
    }

    override fun createFileContent(revisionNumber: VcsRevisionNumber?, file: VirtualFile) = revisionNumber?.let {
        val filePath = file.filePath
        project.jujutsuRepositoryFor(filePath).createContentRevision(filePath, it.contentLocator)
    }

    // TODO When addressing jj-idea-3jo, ensure that these return the correct change ids
    override fun getCurrentRevision(file: VirtualFile) =
        ChangeIdRevisionNumber(project.jujutsuRepositoryFor(file).workingCopy.id)

    override fun getLatestCommittedRevision(file: VirtualFile) =
        project.jujutsuRepositoryFor(file).revisionNumberFor(file.filePath)
}
