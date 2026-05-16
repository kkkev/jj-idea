package `in`.kkkev.jjidea.vcs.merge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vcs.merge.MergeProvider2
import com.intellij.openapi.vcs.merge.MergeSession
import com.intellij.openapi.vcs.merge.MergeSessionEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.ColumnInfo
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.WorkingCopy
import `in`.kkkev.jjidea.jj.conflict.ConflictExtractor
import `in`.kkkev.jjidea.jj.conflict.JjMarkerConflictExtractor
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.possibleJujutsuRepositoryFor
import java.nio.file.Files

class JujutsuMergeProvider(
    private val project: Project,
    private val extractor: ConflictExtractor = JjMarkerConflictExtractor(),
    private val repoFor: (VirtualFile) -> JujutsuRepository? = { project.possibleJujutsuRepositoryFor(it) }
) : MergeProvider2 {
    // Called on a background thread by the merge framework
    override fun loadRevisions(file: VirtualFile): MergeData {
        // The extractor handles all three jj conflict marker styles (snapshot, diff, git).
        // For the working copy, createContentRevision reads the file from disk directly.
        val bytes = repoFor(file)
            ?.createContentRevision(file.filePath, WorkingCopy)
            ?.content
            ?.toByteArray(Charsets.UTF_8)
            ?: file.contentsToByteArray()
        return extractor.extract(bytes)
            ?: throw VcsException("Could not extract conflict data from ${file.name}")
    }

    override fun conflictResolvedForFile(file: VirtualFile) {
        VcsDirtyScopeManager.getInstance(project).fileDirty(file)
    }

    override fun isBinary(file: VirtualFile) = file.fileType.isBinary

    override fun createMergeSession(files: List<VirtualFile>): MergeSession = JujutsuMergeSession(files)

    private inner class JujutsuMergeSession(files: List<VirtualFile>) : MergeSessionEx {
        override fun getMergeInfoColumns(): Array<ColumnInfo<*, *>> = emptyArray()

        override fun canMerge(file: VirtualFile) = !file.isDirectory && !file.fileType.isBinary

        override fun conflictResolvedForFile(file: VirtualFile, resolution: MergeSession.Resolution) =
            conflictResolvedForFiles(listOf(file), resolution)

        override fun conflictResolvedForFiles(files: List<VirtualFile>, resolution: MergeSession.Resolution) =
            files.forEach { VcsDirtyScopeManager.getInstance(project).fileDirty(it) }

        // Called on a background thread inside a modal task
        override fun acceptFilesRevisions(files: List<VirtualFile>, resolution: MergeSession.Resolution) {
            for (file in files) {
                val mergeData = try {
                    loadRevisions(file)
                } catch (_: VcsException) {
                    continue
                }
                val content = when (resolution) {
                    MergeSession.Resolution.AcceptedYours -> mergeData.CURRENT
                    MergeSession.Resolution.AcceptedTheirs -> mergeData.LAST
                    else -> continue
                }
                Files.write(file.toNioPath(), content)
            }
        }
    }
}
