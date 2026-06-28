package `in`.kkkev.jjidea.diffedit

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestFactory
import com.intellij.diff.merge.MergeResult
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Couple
import com.intellij.util.Function
import `in`.kkkev.jjidea.JujutsuBundle

/**
 * Opens IntelliJ's native 3-pane merge window to let the user interactively pick
 * which changes go into the **first commit** of a split operation.
 *
 * ### Protocol
 * Left  = `afterContent` (the revision's full content — all changes).
 * Base  = `baseContent`  (the parent revision's content — no changes).
 * Right = `baseContent`  (same as base — no right-side changes).
 * Output document starts at `baseContent` (nothing in first commit by default).
 *
 * With this setup every change from base→after appears as a *left-only* (blue) change in
 * the merge window. The user accepts (>>) the changes they want in the first commit and
 * leaves the rest as-is. On "Apply", the merged result is the first-commit content;
 * the `jj split` engine then derives the second commit from the difference.
 *
 * Returns the merged text on RESOLVED/LEFT/RIGHT, null on CANCEL (caller keeps prior state).
 */
object HunkMergePicker {
    /**
     * Open the merge window and return the chosen first-commit content, or null if cancelled.
     *
     * Must be called on the EDT (modal dialog environment).
     *
     * @param project           The current project.
     * @param fileName          Display name of the file (e.g. "Auth.kt") — used for titles.
     * @param fileType          File type for syntax highlighting.  Use [fileTypeFor] to resolve.
     * @param baseContent       Parent (before) content of the file.
     * @param afterContent      Revision (after) content of the file — all changes.
     * @param commitLabel       Name of the target commit as shown in the split dialog (e.g. "Parent"
     *                          or "Second"). Used in the window title, apply button, and cancel
     *                          message so the merge picker's wording matches the file list.
     */
    fun pickFirstCommitContent(
        project: Project,
        fileName: String,
        fileType: FileType,
        baseContent: String,
        afterContent: String,
        commitLabel: String
    ): String? {
        var resolvedContent: String? = null

        // Output document: initially baseContent so the user starts with nothing accepted.
        val outputDocument = EditorFactory.getInstance().createDocument(baseContent)

        val title = JujutsuBundle.message("dialog.split.merge.title", commitLabel, fileName)
        val contentTitles = listOf(
            JujutsuBundle.message("dialog.split.merge.side.changes"),
            JujutsuBundle.message("dialog.split.merge.side.firstCommit", commitLabel),
            JujutsuBundle.message("dialog.split.merge.side.original")
        )

        val request = DiffRequestFactory.getInstance().createMergeRequest(
            project,
            fileType,
            outputDocument,
            listOf(afterContent, baseContent, baseContent),
            title,
            contentTitles
        ) { result ->
            if (result != MergeResult.CANCEL) {
                resolvedContent = outputDocument.text
            }
        }

        // Rename resolve buttons to match split context (not conflict-resolution wording).
        request.putUserData(
            DiffUserDataKeysEx.MERGE_ACTION_CAPTIONS,
            Function { result ->
                when (result) {
                    MergeResult.RESOLVED -> JujutsuBundle.message("dialog.split.merge.action.apply", commitLabel)
                    MergeResult.CANCEL -> JujutsuBundle.message("dialog.split.merge.action.cancel")
                    else -> null // LEFT/RIGHT: keep platform defaults
                }
            }
        )

        // Replace the "discard changes and cancel merge?" dialog with split-specific wording.
        request.putUserData(
            DiffUserDataKeysEx.MERGE_CANCEL_MESSAGE,
            Couple.of(
                JujutsuBundle.message("dialog.split.merge.cancel.title"),
                JujutsuBundle.message("dialog.split.merge.cancel.message", commitLabel)
            )
        )

        DiffManager.getInstance().showMerge(project, request)
        return resolvedContent
    }

    /** Resolve the [FileType] to use for syntax highlighting from a file name. */
    fun fileTypeFor(fileName: String): FileType =
        FileTypeManager.getInstance().getFileTypeByFileName(fileName)
            .takeIf { it != com.intellij.openapi.fileTypes.UnknownFileType.INSTANCE }
            ?: PlainTextFileType.INSTANCE
}
