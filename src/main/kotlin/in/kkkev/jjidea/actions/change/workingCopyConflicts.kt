package `in`.kkkev.jjidea.actions.change

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
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
 * The single funnel into conflict resolution. Always goes through [JujutsuConflictResolver]'s
 * scratch-document merge tool - never [com.intellij.openapi.vcs.AbstractVcsHelper.showMergeDialog]
 * directly, which silently discards a side of the conflict on cancel (GitHub #63).
 */
internal fun resolveConflicts(project: Project, files: List<VirtualFile>) {
    if (files.isEmpty()) return
    val mergeProvider = project.possibleJujutsuVcs?.mergeProvider ?: return
    JujutsuConflictResolver(project, mergeProvider).resolve(files)
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
