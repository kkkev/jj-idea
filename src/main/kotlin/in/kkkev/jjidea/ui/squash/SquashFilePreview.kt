package `in`.kkkev.jjidea.ui.squash

import com.intellij.openapi.vcs.changes.Change
import `in`.kkkev.jjidea.diffedit.HunkPicker
import `in`.kkkev.jjidea.ui.common.FileContents

/**
 * Load both sides of [change]'s own content — its parent → itself — plus the file type for
 * syntax highlighting. Hits `jj file show` via the change's
 * [com.intellij.openapi.vcs.changes.ContentRevision]s (see [in.kkkev.jjidea.jj.ChangeService]
 * for how those are built, including merge-parent reconstruction) — call off the EDT. Both sides
 * default to `""` when the corresponding `ContentRevision` has no content (added/deleted files).
 *
 * Returns null only if both sides are unreadable (shouldn't happen for a real file change) — the
 * [in.kkkev.jjidea.ui.common.HunkPickPreviewController] loader signature for [SquashIntoDialog].
 */
internal fun loadSquashFileData(change: Change): FileContents? {
    val before = change.beforeRevision?.content
    val after = change.afterRevision?.content
    if (before == null && after == null) return null
    val fileName = (change.afterRevision ?: change.beforeRevision)!!.file.name
    return FileContents(before = before ?: "", after = after ?: "", fileType = HunkPicker.fileTypeFor(fileName))
}

/**
 * Right-hand pane content for the squash preview — the destination's result for this file.
 *
 * [override] is unused today (always null); it's the seam jj-idea-4q7m's hunk picker will fill
 * in, exactly mirroring [in.kkkev.jjidea.ui.split.SplitDialog.computePreviewLeftContent].
 */
internal fun computePreviewAfterContent(
    isIncluded: Boolean,
    override: String?,
    before: String,
    after: String
): String = when {
    override != null -> override
    isIncluded -> after
    else -> before
}
