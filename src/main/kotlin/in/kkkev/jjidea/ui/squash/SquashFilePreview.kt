package `in`.kkkev.jjidea.ui.squash

import com.intellij.openapi.vcs.changes.Change
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.diffedit.HunkPicker
import `in`.kkkev.jjidea.ui.common.DiffPane
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

/**
 * Describe the squash preview's diff state as a pair of (before title, destination title) label
 * fragments. Unlike [in.kkkev.jjidea.ui.split.SplitDialog]'s `describeSplitState`, the **before**
 * side here never varies — it's always the source's own pre-change content — so its title is
 * constant; only the **destination** side (see [computePreviewAfterContent]) changes: an unticked
 * (nothing moves) file reads "Before" / "Destination (unchanged)"; a fully ticked file reads
 * "Before" / "Destination (all changes)"; anything else (reserved for jj-idea-4q7m's partial hunk
 * picks) is "Destination (partial)".
 */
internal fun describeSquashState(content: String, before: String, after: String): Pair<String, String> {
    val beforeTitle = JujutsuBundle.message("dialog.squash.preview.legend.before")
    val afterLabel = JujutsuBundle.message("dialog.squash.preview.legend.destination")
    val afterTitle = when (content) {
        before -> JujutsuBundle.message("dialog.squash.preview.after.unchanged", afterLabel)
        after -> JujutsuBundle.message("dialog.squash.preview.after.allChanges", afterLabel)
        else -> JujutsuBundle.message("dialog.squash.preview.after.partial", afterLabel)
    }
    return Pair(beforeTitle, afterTitle)
}

/**
 * The (left, right) [DiffPane]s for the squash preview: left is always [FileContents.before] (the
 * source's own pre-change content, which never varies), right is [content] (the resolved
 * destination content). Titles come from [describeSquashState]; text and title are paired per
 * side so a pane can't disagree with its own title (jj-idea-jb2q).
 */
internal fun squashPreviewPanes(content: String, contents: FileContents): Pair<DiffPane, DiffPane> {
    val (beforeTitle, afterTitle) = describeSquashState(content, contents.before, contents.after)
    return Pair(DiffPane(contents.before, beforeTitle), DiffPane(content, afterTitle))
}
