package `in`.kkkev.jjidea.diffedit

import `in`.kkkev.jjidea.JujutsuBundle

/**
 * Pane titles and arrow tooltips for one [HunkPickerDialog] session.
 *
 * [HunkPickerDialog]/[HunkArrowDiffExtension]'s mechanics are polarity-agnostic — the middle pane
 * always starts at either [HunkPickerDialog]'s `baseContent` (the left, fixed pane) or
 * `afterContent` (the right, fixed pane) and moves between them one hunk at a time. Only the
 * *wording* differs between callers: Split's middle pane represents the content staying at the
 * original revision id ("parent"), with hunks moving out to a new child; Squash's middle pane
 * represents the content landing in the destination, with hunks moving in from the source. See
 * [forSplit] / [forSquash].
 *
 * @param leftTitle           Title of the fixed left pane.
 * @param middleTitle         Title of the live middle pane.
 * @param rightTitle          Title of the fixed right pane.
 * @param middleArrowTooltip  Tooltip for the arrow shown on the middle pane's gutter (appears
 *                            wherever the middle pane currently differs from the left pane;
 *                            clicking it pulls the left pane's content in for that hunk).
 * @param rightArrowTooltip   Tooltip for the arrow shown on the right pane's gutter (appears
 *                            wherever the middle pane currently differs from the right pane;
 *                            clicking it pulls the right pane's content in for that hunk).
 */
data class HunkPickerLabels(
    val leftTitle: String,
    val middleTitle: String,
    val rightTitle: String,
    val middleArrowTooltip: String,
    val rightArrowTooltip: String
) {
    companion object {
        /**
         * Split's wording: left = parent (stays), right = child (moves to). Reproduces the
         * strings this dialog used before it was generalized for Squash, so MT-SPLIT's wording
         * is unchanged.
         */
        fun forSplit(staysLabel: String, movesToLabel: String) = HunkPickerLabels(
            leftTitle = staysLabel,
            middleTitle = JujutsuBundle.message("dialog.hunks.split.side.parent", staysLabel),
            rightTitle = JujutsuBundle.message("dialog.hunks.split.side.moves", movesToLabel),
            middleArrowTooltip = JujutsuBundle.message("dialog.hunks.split.arrow.toChild", movesToLabel),
            rightArrowTooltip = JujutsuBundle.message("dialog.hunks.split.arrow.toParent", staysLabel)
        )

        /**
         * Squash's wording: left = the source's own pre-change content (nothing squashed), right
         * = the source's own full change (fully squashed). The middle pane represents the
         * destination's resulting content for this file.
         */
        fun forSquash(sourceLabel: String, destinationLabel: String) = HunkPickerLabels(
            leftTitle = JujutsuBundle.message("dialog.hunks.squash.side.before"),
            middleTitle = JujutsuBundle.message("dialog.hunks.squash.side.destination", destinationLabel),
            rightTitle = JujutsuBundle.message("dialog.hunks.squash.side.source", sourceLabel),
            // The middle-gutter arrow appears where the middle pane currently differs from the
            // left (before/unsquashed) pane and, when clicked, pulls that pane's content back in
            // — i.e. it un-squashes the hunk, keeping it in the source.
            middleArrowTooltip = JujutsuBundle.message("dialog.hunks.squash.arrow.toSource", sourceLabel),
            // The right-gutter arrow appears where the middle pane currently differs from the
            // right (after/fully-squashed) pane and pulls that pane's content in — squashing the
            // hunk into the destination.
            rightArrowTooltip = JujutsuBundle.message("dialog.hunks.squash.arrow.toDestination", destinationLabel)
        )
    }
}
