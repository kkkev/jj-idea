package `in`.kkkev.jjidea.vcs.merge

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.merge.MergeResult
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vcs.merge.MergeProvider
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.diffedit.HunkPicker
import `in`.kkkev.jjidea.jj.conflict.ConflictInfo
import `in`.kkkev.jjidea.jj.conflict.conflictRegistry
import java.nio.file.Files

/**
 * Resolves conflicted files one at a time via IntelliJ's three-way merge tool, without ever
 * handing the platform the real file's [com.intellij.openapi.editor.Document] as merge output.
 *
 * ### Why this exists (GitHub #63)
 * [com.intellij.openapi.vcs.AbstractVcsHelper.showMergeDialog] drives the platform's
 * `MultipleFileMergeDialog`, which uses the real file's Document as the editable "merged
 * result" pane and resets it to the base/original side at init
 * (`MergeConflictModel.setInitialOutputContent`). On IntelliJ 2026.2's default iterative merge
 * flow, closing the tool without resolving (`MergeResult.CANCEL`) no longer restores that
 * original content (`MergeUtil.shouldRestoreOriginalContentOnCancel` returns false whenever the
 * request carries iterative data), and the dialog saves the document unconditionally — silently
 * discarding a side of the conflict.
 *
 * That's harmless for git, where the index (not the working file) is the source of truth for a
 * conflict. It's destructive for jj: there is no index, so the working file's conflict markers
 * *are* the conflict record, re-parsed from scratch on every snapshot. Losing the markers loses
 * the conflict.
 *
 * ### The fix
 * Give the merge tool a throwaway output [com.intellij.openapi.editor.Document] — the same
 * pattern [in.kkkev.jjidea.diffedit.HunkPickerDialog] already uses safely for `jj split` — and
 * only write the real file
 * when the tool reports a non-cancel result. Files resolve one at a time; cancelling stops the
 * remaining queue, matching `showMergeDialog`'s one-shot-per-invocation semantics.
 *
 * ### Known residual gap: the native Commit tool window's own "Resolve" link (jj-idea-ddcd)
 * The standard Commit / Local Changes tool window renders its own "Merge Conflicts" node
 * (`ChangesBrowserConflictsNode` in the platform) with a built-in "Resolve" link that calls
 * `AbstractVcsHelper.showMergeDialog` directly — bypassing this class entirely — so it has the
 * exact same discard-on-cancel bug described above. jj-idea cannot intercept, replace, or
 * suppress that link: there's no extension point to remove/override it, and the one EP that can
 * add an *alternative* link next to it (`MergeResolveActionProvider`) is unusable — it's
 * `@ApiStatus.Internal` and only exists on 2026.2+, and the only way to register into it at
 * runtime without a hard compile-time dependency on that missing class is a platform method
 * marked `@TestOnly` (see jj-idea-ddcd's notes for the full investigation). Since jj-idea-wb5l,
 * this is masked for the common case by hiding the standard Commit tool window entirely for
 * jj-only projects; the gap only remains reachable if a user opts back into that window, or in
 * a mixed jj + Git project.
 *
 * The Working Copy tool window's own replacement for that grouped affordance is
 * [in.kkkev.jjidea.ui.common.JujutsuConflictsNode] (GitHub #56, jj-idea-uoeg) — a "Merge
 * Conflicts" tag node pinned to the top of its changes tree, whose "Resolve" link and the
 * companion `Jujutsu.ResolveAllConflicts` toolbar action both route through this class.
 *
 * @param resolveOne Runs the merge tool for one file and returns the resolved bytes, or null if
 *   cancelled. Overridable for testing; the default opens the real three-way merge tool.
 * @param writeResolved Writes resolved bytes back to the file. Overridable for testing.
 * @param deleteResolved Deletes the file from disk. Used instead of [writeResolved] when the
 *   conflict was modify/delete and the user resolved it to the deleted side (see [resolve]).
 *   Overridable for testing.
 * @param conflictInfoFor Looks up the [ConflictInfo] for a file, if known. Overridable for
 *   testing; the default consults [in.kkkev.jjidea.jj.conflict.JujutsuConflictRegistry].
 */
class JujutsuConflictResolver(
    private val project: Project,
    private val mergeProvider: MergeProvider,
    private val resolveOne: (VirtualFile, MergeData) -> ByteArray? = { file, data ->
        defaultResolveOne(project, file, data)
    },
    private val writeResolved: (VirtualFile, ByteArray) -> Unit = { file, bytes ->
        Files.write(file.toNioPath(), bytes)
    },
    private val deleteResolved: (VirtualFile) -> Unit = { file ->
        Files.deleteIfExists(file.toNioPath())
    },
    private val conflictInfoFor: (VirtualFile) -> ConflictInfo? = { project.conflictRegistry.get(it) }
) {
    /**
     * Resolves each conflicted file in turn. Stops (leaving any remaining files untouched) as
     * soon as the user cancels the merge tool for one of them.
     *
     * For a modify/delete conflict, an empty resolved result means the user picked the deleted
     * side in the merge tool - the deleted side's pane is empty because there's no content on
     * that side. Writing those empty bytes to disk would leave an empty file behind instead of
     * actually deleting it, so that case is routed to [deleteResolved] instead of [writeResolved].
     */
    fun resolve(files: List<VirtualFile>) {
        for (file in files) {
            val mergeData = try {
                mergeProvider.loadRevisions(file)
            } catch (_: VcsException) {
                continue // Already resolved (or no conflict markers found) - nothing to do.
            }

            val resolved = resolveOne(file, mergeData) ?: return // Cancelled: stop the queue.

            if (resolved.isEmpty() && conflictInfoFor(file)?.isModifyDelete == true) {
                deleteResolved(file)
            } else {
                writeResolved(file, resolved)
            }
            // Marks the file dirty and invalidates the repo so jj re-snapshots and clears the
            // conflict decoration (see JujutsuMergeProvider.refreshResolved).
            mergeProvider.conflictResolvedForFile(file)
        }
    }

    private companion object {
        fun defaultResolveOne(project: Project, file: VirtualFile, mergeData: MergeData): ByteArray? {
            // Scratch document, never the real file's - see class doc for why that matters.
            val outputDocument = EditorFactory.getInstance().createDocument("")
            var resolved: ByteArray? = null

            val request = DiffRequestFactory.getInstance().createMergeRequest(
                project,
                HunkPicker.fileTypeFor(file.name),
                outputDocument,
                listOf(mergeData.CURRENT, mergeData.ORIGINAL, mergeData.LAST)
                    .map { String(it, Charsets.UTF_8) },
                JujutsuBundle.message("dialog.resolve.conflict.title", file.name),
                listOf(
                    JujutsuBundle.message("merge.column.yours"),
                    JujutsuBundle.message("merge.column.result"),
                    JujutsuBundle.message("merge.column.theirs")
                )
            ) { result ->
                if (result != MergeResult.CANCEL) {
                    resolved = outputDocument.text.toByteArray(Charsets.UTF_8)
                }
            }

            DiffManager.getInstance().showMerge(project, request)
            return resolved
        }
    }
}
