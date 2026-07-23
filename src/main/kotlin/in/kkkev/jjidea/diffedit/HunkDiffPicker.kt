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
 * Lets the user interactively pick which hunks of a file's change move to the **child**
 * commit of a split operation, via IntelliJ's 3-way merge widget.
 *
 * ### Roles — fixed, never tick-dependent
 * - **Left** = `baseContent` — the file's state before any of this change, always.
 * - **Right** = `afterContent` — the source commit's full content (also always the child's
 *   eventual content — a structural invariant of `jj split`), always.
 * - **Middle** = editable "Parent" content, seeded from the caller-supplied [initialContent].
 *
 * An earlier version of this picker made *which side* carried the live/clickable hunks depend
 * on the file's tick state (to try to avoid a 3-way merge's usual "pick left or right"
 * framing). That was a mistake: with only one side ever live, there was nothing to act on
 * when the editable content already matched that side (e.g. an untouched file showed an empty
 * diff, nothing to pick), and no way to reverse a pick once made (the read-only side never
 * changes, so once editable matched it there was nothing left to undo *with*).
 *
 * Keeping both sides permanently fixed gives simple, constant semantics instead: accepting a
 * change from the **left** removes that hunk from Parent (sends it to the child); accepting
 * from the **right** adds that hunk to Parent (pulls it from the child). Both are available
 * for the same hunk whenever it differs from the current middle content, so a hunk can be
 * moved in either direction as many times as the user likes before finishing — not a one-shot
 * pick.
 */
object HunkDiffPicker {
    /**
     * Open the picker and return the resulting parent content, or null if cancelled (caller
     * keeps prior state).
     *
     * Must be called on the EDT (modal dialog).
     *
     * @param project        The current project.
     * @param fileName       Display name of the file (e.g. "Auth.kt") — used for the dialog title.
     * @param fileType       File type for syntax highlighting. Use [fileTypeFor] to resolve.
     * @param baseContent    Parent (before) content of the file — the fixed left pane.
     * @param afterContent   Revision (after) content of the file — the fixed right pane.
     * @param initialContent Starting content for the editable middle pane: pass any existing
     *                       partial override to resume it, or a tick-derived default otherwise.
     * @param parentLabel    Name of the parent commit as shown in the split dialog (e.g. "Parent" or "Second").
     * @param childLabel     Name of the child commit as shown in the split dialog (e.g. "Child" or "First").
     */
    fun pickParentContent(
        project: Project,
        fileName: String,
        fileType: FileType,
        baseContent: String,
        afterContent: String,
        initialContent: String,
        parentLabel: String,
        childLabel: String
    ): String? {
        var resolvedContent: String? = null

        val outputDocument = EditorFactory.getInstance().createDocument(initialContent)

        val title = JujutsuBundle.message("dialog.split.hunks.title", fileName)
        val contentTitles = listOf(
            JujutsuBundle.message("dialog.split.hunks.side.before"),
            parentLabel,
            childLabel
        )

        val request = DiffRequestFactory.getInstance().createMergeRequest(
            project,
            fileType,
            outputDocument,
            listOf(baseContent, initialContent, afterContent),
            title,
            contentTitles
        ) { result ->
            if (result != MergeResult.CANCEL) {
                resolvedContent = outputDocument.text
            }
        }

        request.putUserData(
            DiffUserDataKeysEx.MERGE_ACTION_CAPTIONS,
            Function { result ->
                when (result) {
                    MergeResult.RESOLVED -> JujutsuBundle.message("dialog.split.hunks.action.apply")
                    MergeResult.CANCEL -> JujutsuBundle.message("dialog.split.hunks.action.cancel")
                    else -> null // LEFT/RIGHT: keep platform defaults
                }
            }
        )

        request.putUserData(
            DiffUserDataKeysEx.MERGE_CANCEL_MESSAGE,
            Couple.of(
                JujutsuBundle.message("dialog.split.hunks.cancel.title"),
                JujutsuBundle.message("dialog.split.hunks.cancel.message", parentLabel)
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
