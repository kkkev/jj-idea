package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.vcs.filePath
import `in`.kkkev.jjidea.vcs.relativeTo

/**
 * The desired diff-editor `$right` content for a single file in a hunk-level split or squash
 * operation — see [in.kkkev.jjidea.diffedit.DiffEditTool]'s KDoc for the protocol. Shared between
 * [in.kkkev.jjidea.ui.split.SplitDialog] (where `$right` becomes the content that stays at the
 * original revision id) and [in.kkkev.jjidea.ui.squash.SquashIntoDialog] (where `$right` becomes
 * the destination's new content) — the two dialogs differ only in what `content` *means*, never
 * in how it's plumbed through the staging tree.
 *
 * @param relPath   Repo-relative POSIX path (used as a key in the staging tree).
 * @param filePath  IntelliJ [FilePath] for tree display.
 * @param content   Desired `$right` content, or null if this file is entirely absent from the
 *                  staging tree (its `$right` content is left to [in.kkkev.jjidea.diffedit.
 *                  DiffEditTool]'s default: restored unchanged from `$left`).
 */
data class FileHunkContent(
    val relPath: String,
    val filePath: FilePath,
    val content: String?
)

/**
 * Aggregated per-file `$right` content for a hunk-level split or squash operation.
 *
 * @param files        One [FileHunkContent] per changed file (or a subset that has an explicit
 *   assignment; uncovered files fall back to the file checkbox).
 * @param deletedPaths Repo-relative POSIX paths whose *deletion* should be written into `$right`
 *   — distinct from [files] because the staging tree can't otherwise express "delete this file"
 *   (an absent staging entry means "leave unchanged", not "delete"). See [in.kkkev.jjidea.diffedit.
 *   DiffEditTool.buildStagingTree]'s `deletedPaths` parameter.
 */
class HunkSelection(val files: List<FileHunkContent>, val deletedPaths: Set<String> = emptySet()) {
    /** Number of files with any explicit (non-null) content. */
    val explicitContentFileCount: Int get() = files.count { it.content != null }

    /** Number of files whose content is null (left to the diff editor's default). */
    val defaultedFileCount: Int get() = files.count { it.content == null }

    /**
     * True if any file has explicitly-computed partial content — i.e. content that was
     * produced by the hunk picker rather than being a whole-file default. Always true when
     * this object exists (its existence implies partial selection).
     */
    val hasPartialFiles: Boolean get() = true

    /**
     * Build the `perFileContent` map consumed by
     * [in.kkkev.jjidea.diffedit.DiffEditTool.buildStagingTree].
     *
     * Maps repo-relative POSIX path → desired `$right` content (null = default/unchanged).
     */
    fun buildPerFileContent(): Map<String, String?> =
        files.associate { it.relPath to it.content }

    /**
     * FilePaths of files with non-null explicit content.
     * Used to update the file selection panel after a hunk-picking session.
     */
    fun explicitContentFilePaths(): List<FilePath> =
        files.filter { it.content != null }.map { it.filePath }
}

/**
 * Build a [HunkSelection] from [changes]: for each file, an explicit [overrides] entry wins;
 * otherwise a ticked ([isIncluded]) file whose own change deletes it ([isDeletion]) is recorded
 * in [HunkSelection.deletedPaths] instead of being given content (content can't express deletion
 * — see [FileHunkContent]); anything else defers to [contentFor], which encodes each dialog's own
 * split/squash polarity (see [in.kkkev.jjidea.ui.split.SplitDialog] and
 * [in.kkkev.jjidea.ui.squash.SquashIntoDialog] for their respective `contentFor` closures).
 *
 * Pure and dialog-agnostic — see `HunkSelectionTest`/`HunkSelectionScaleTest`.
 */
fun buildHunkSelection(
    changes: List<Change>,
    root: VirtualFile,
    overrides: Map<FilePath, String>,
    isIncluded: (FilePath) -> Boolean,
    isDeletion: (Change) -> Boolean,
    contentFor: (change: Change, included: Boolean) -> String?
): HunkSelection {
    val deletedPaths = mutableSetOf<String>()
    val files = changes.map { change ->
        val fp = change.filePath
        val relPath = fp.relativeTo(root)
        val included = isIncluded(fp)
        val override = overrides[fp]
        val content: String? = when {
            override != null -> override
            included && isDeletion(change) -> {
                deletedPaths += relPath
                null
            }
            else -> contentFor(change, included)
        }
        FileHunkContent(relPath = relPath, filePath = fp, content = content)
    }
    return HunkSelection(files, deletedPaths)
}
