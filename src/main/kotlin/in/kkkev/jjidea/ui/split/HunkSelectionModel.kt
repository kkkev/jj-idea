package `in`.kkkev.jjidea.ui.split

import com.intellij.openapi.vcs.FilePath

/**
 * The desired first-commit content for a single file in a hunk-level split operation.
 *
 * @param relPath   Repo-relative POSIX path (used as a key in the staging tree).
 * @param filePath  IntelliJ [FilePath] for tree display.
 * @param content   First-commit content, or null if the file goes entirely to the second commit.
 */
data class FileFirstCommit(
    val relPath: String,
    val filePath: FilePath,
    val content: String?
)

/**
 * Aggregated per-file first-commit content for a hunk-level split operation.
 *
 * When at least one file has a [FileFirstCommit] with content that differs from the whole
 * after-content (or is null when it "should" be included), the split must go through the
 * diff-editor path ([in.kkkev.jjidea.diffedit.DiffEditTool]) rather than the fast file-level path.
 *
 * @param files One [FileFirstCommit] per changed file (or a subset of changed files that
 *   have an explicit first-commit assignment; uncovered files fall back to the file checkbox).
 */
class SplitHunkSelection(val files: List<FileFirstCommit>) {
    /** Number of files that have any first-commit content (non-null). */
    val firstCommitFileCount: Int get() = files.count { it.content != null }

    /** Number of files whose content is null (entirely in second commit). */
    val secondCommitFileCount: Int get() = files.count { it.content == null }

    /**
     * True if any file has explicitly-computed partial content — i.e. content that was
     * produced by the merge picker rather than being the verbatim after-content.
     * Always true when this object exists (its existence implies partial selection).
     */
    val hasPartialFiles: Boolean get() = true

    /**
     * Build the `perFileContent` map consumed by
     * [in.kkkev.jjidea.diffedit.DiffEditTool.buildStagingTree].
     *
     * Maps repo-relative POSIX path → desired first-commit content (null = absent from first commit).
     */
    fun buildPerFileContent(): Map<String, String?> =
        files.associate { it.relPath to it.content }

    /**
     * FilePaths of files with non-null first-commit content.
     * Used to update the file selection panel after a merge session.
     */
    fun firstCommitFilePaths(): List<FilePath> =
        files.filter { it.content != null }.map { it.filePath }
}
