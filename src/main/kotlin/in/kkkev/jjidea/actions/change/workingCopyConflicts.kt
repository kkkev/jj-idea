package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.conflict.firstConflictBlockOffset
import `in`.kkkev.jjidea.vcs.filterInJujutsuRepo
import `in`.kkkev.jjidea.vcs.merge.JujutsuConflictResolver
import `in`.kkkev.jjidea.vcs.possibleJujutsuVcs

/**
 * All files with active conflicts in the working copy, across every jj repo in the project.
 *
 * Read live rather than cached: a stale snapshot could hand the merge dialog a file jj has
 * already resolved (e.g. resolved externally, or in a prior invocation), which throws in
 * [in.kkkev.jjidea.vcs.merge.JujutsuMergeProvider] (jj-idea-3cvb).
 */
internal fun workingCopyConflicts(project: Project): List<VirtualFile> =
    ChangeListManager.getInstance(project).allChanges
        .filterInJujutsuRepo(project)
        .filter { it.fileStatus == FileStatus.MERGED_WITH_CONFLICTS }
        .mapNotNull { it.virtualFile }

/**
 * The explicit "Open Merge Tool" path. Always goes through [JujutsuConflictResolver]'s
 * scratch-document merge tool - never [com.intellij.openapi.vcs.AbstractVcsHelper.showMergeDialog]
 * directly, which silently discards a side of the conflict on cancel (GitHub #63).
 *
 * Since jj-idea-z9tp this is the secondary action at every conflict entry point; the default is
 * [resolveConflictsInEditor].
 */
internal fun openMergeTool(project: Project, files: List<VirtualFile>) {
    if (files.isEmpty()) return
    val mergeProvider = project.possibleJujutsuVcs?.mergeProvider ?: return
    JujutsuConflictResolver(project, mergeProvider).resolve(files)
}

/** The one file an editor-first resolve opens out of [files]: first in path order, O(files). */
internal fun firstConflictToOpen(files: List<VirtualFile>): VirtualFile? = files.minByOrNull { it.path }

/**
 * The default conflict-resolve gesture (design doc S6, jj-idea-z9tp): open the conflicted file in
 * the editor - where the S1 banner and S2 gutter live - with the caret on its first conflict
 * block. Only the first file (path order) is opened, never one tab per file; the Merge Conflicts
 * node and the banner lead to the rest. [open] is the navigation seam for tests.
 */
internal fun resolveConflictsInEditor(
    project: Project,
    files: List<VirtualFile>,
    open: (VirtualFile, Int) -> Unit = { file, offset -> OpenFileDescriptor(project, file, offset).navigate(true) }
) {
    val file = firstConflictToOpen(files) ?: return
    val offset = FileDocumentManager.getInstance().getDocument(file)
        ?.let { firstConflictBlockOffset(it.immutableCharSequence) } ?: 0
    open(file, offset)
}

/**
 * Whether `Jujutsu.ResolveAllConflicts` should be enabled/visible: purely a function of whether
 * the working copy has conflicts, independent of any tree/editor selection. Pulled out of
 * `ResolveAllConflictsAction.update` so it's testable without mocking
 * [com.intellij.openapi.actionSystem.AnActionEvent] (`getProject()` is a final platform method
 * mockk can't intercept in this project's test setup).
 */
internal fun hasWorkingCopyConflicts(project: Project): Boolean =
    project.possibleJujutsuVcs != null && workingCopyConflicts(project).isNotEmpty()
