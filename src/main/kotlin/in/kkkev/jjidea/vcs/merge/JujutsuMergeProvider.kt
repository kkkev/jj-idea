package `in`.kkkev.jjidea.vcs.merge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vcs.merge.MergeProvider
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.conflict.ConflictExtractor
import `in`.kkkev.jjidea.jj.conflict.JjMarkerConflictExtractor

// TODO: Upgrade to MergeProvider2 to support bulk Accept Yours / Accept Theirs
class JujutsuMergeProvider(
    private val project: Project,
    private val extractor: ConflictExtractor = JjMarkerConflictExtractor()
) : MergeProvider {
    // Called on a background thread by the merge framework; contentsToByteArray() I/O is safe here
    override fun loadRevisions(file: VirtualFile): MergeData =
        extractor.extract(file.contentsToByteArray())
            ?: throw VcsException("Could not extract conflict data from ${file.name}")

    override fun conflictResolvedForFile(file: VirtualFile) {
        // JJ auto-detects resolution when conflict markers are absent on next status refresh
    }

    override fun isBinary(file: VirtualFile) = file.fileType.isBinary
}
