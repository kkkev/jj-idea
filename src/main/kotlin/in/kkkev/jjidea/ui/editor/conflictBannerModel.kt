package `in`.kkkev.jjidea.ui.editor

import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.conflict.ExtractedConflict

/** Max length of a side's label as shown on the banner's action link - see [SideAction.displayLabel]. */
private const val MAX_SIDE_LABEL_LENGTH = 24

/**
 * One "Accept &lt;label&gt;" link: the full jj-native [label] (used as the link's tooltip, and by
 * tests), and the `jj resolve --tool` name it runs.
 */
internal data class SideAction(val label: String, val tool: String) {
    /**
     * [label], shortened to fit the banner's action link on a narrow editor. jj's own marker
     * header text (change id + commit id + description, sometimes plus a role annotation like
     * `(rebased revision)`) has no fixed shape to parse for a "smarter" truncation, so this is a
     * plain right-truncation with an ellipsis - the full text is still one hover away via the
     * link's tooltip ([JujutsuConflictEditorNotificationProvider]).
     */
    val displayLabel: String get() = truncateForBanner(label, MAX_SIDE_LABEL_LENGTH)
}

/** Right-truncates [text] to [maxLength] chars (including the ellipsis), trimming trailing whitespace first. */
internal fun truncateForBanner(text: String, maxLength: Int): String =
    if (text.length <= maxLength) text else text.take(maxLength - 1).trimEnd() + "…"

/**
 * Pure, platform-free model backing [JujutsuConflictEditorNotificationProvider]'s banner - kept
 * separate from the [com.intellij.ui.EditorNotificationPanel] construction so the label/text
 * logic is unit-testable headlessly, like the rest of the conflict-parsing suite.
 *
 * [acceptCurrent]/[acceptLast] are null when [conflictBannerModel] was built from a `null`
 * [ExtractedConflict] (extraction failed) - there is then no reliable label or `:ours`/`:theirs`
 * orientation to offer an accept action for, so the banner shows only the "Open Merge Tool" link.
 */
internal data class ConflictBannerModel(val acceptCurrent: SideAction?, val acceptLast: SideAction?) {
    fun textFor(blockCount: Int): String = if (blockCount == 1) {
        JujutsuBundle.message("notification.conflict.count.one")
    } else {
        JujutsuBundle.message("notification.conflict.count.many", blockCount)
    }
}

/**
 * Labels fall back to "Side #1"/"Side #2" exactly like [JujutsuConflictResolver]'s merge-tool
 * pane titles ([in.kkkev.jjidea.vcs.merge.JujutsuConflictResolver]) - the same jj markers carry
 * no commit-identifying text (snapshot style, or an unresolved boilerplate header) in both places.
 */
internal fun conflictBannerModel(conflict: ExtractedConflict?): ConflictBannerModel {
    if (conflict == null) return ConflictBannerModel(acceptCurrent = null, acceptLast = null)
    val side1Label = JujutsuBundle.message("merge.column.side1")
    val side2Label = JujutsuBundle.message("merge.column.side2")
    return ConflictBannerModel(
        acceptCurrent = SideAction(conflict.currentTitle ?: side1Label, conflict.toolForCurrent),
        acceptLast = SideAction(conflict.lastTitle ?: side2Label, conflict.toolForLast)
    )
}
