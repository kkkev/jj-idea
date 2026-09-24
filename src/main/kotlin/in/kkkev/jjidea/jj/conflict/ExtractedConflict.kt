package `in`.kkkev.jjidea.jj.conflict

import com.intellij.openapi.vcs.merge.MergeData

/**
 * The result of parsing a conflicted file's markers: the three-way [mergeData] IntelliJ's merge
 * tool needs, plus display metadata pulled from jj's own marker headers (GitHub #112).
 *
 * jj embeds a commit + role label in every marker header (e.g. `"my change" (rebased revision)`).
 * [JjMarkerConflictExtractor] uses that role text to decide which side belongs in
 * [MergeData.CURRENT] ("Yours") vs [MergeData.LAST] ("Theirs") for rebase-shaped conflicts,
 * since jj's own positional/rendering choice for those two sides is not a reliable signal of
 * which one is the user's own change (see the extractor's class doc). [currentTitle] and
 * [lastTitle] are jj's label text for whichever side actually ended up in `CURRENT`/`LAST`, so
 * they always describe the content the pane is showing, null when jj's markers carry no
 * commit-identifying text (snapshot style, or an unresolved boilerplate header).
 *
 * @param currentIsJjSide1 Whether the content placed in [MergeData.CURRENT] is jj's conflict
 *   side #1 (the side `jj resolve --tool :ours` picks) rather than side #2 (`:theirs`). True
 *   whenever no reorientation applied (the pre-existing, unswapped mapping).
 * @param currentAlternateTitle [ConflictSide.alternateLabel] for whichever side ended up in
 *   `CURRENT` - almost always null (see that field's doc); a caller whose [currentTitle] happens
 *   to collide with [lastTitle] (e.g. both name the same commit due to divergence) can fall back
 *   to this instead of showing the same label for both sides.
 * @param lastAlternateTitle The [LAST][MergeData.LAST]-side counterpart of [currentAlternateTitle].
 */
data class ExtractedConflict(
    val mergeData: MergeData,
    val currentTitle: String?,
    val lastTitle: String?,
    val currentIsJjSide1: Boolean,
    val currentAlternateTitle: String? = null,
    val lastAlternateTitle: String? = null
) {
    /** The `jj resolve --tool` name that accepts the side shown as [currentTitle] ("Yours"). */
    val toolForCurrent: String get() = if (currentIsJjSide1) ":ours" else ":theirs"

    /** The `jj resolve --tool` name that accepts the side shown as [lastTitle] ("Theirs"). */
    val toolForLast: String get() = if (currentIsJjSide1) ":theirs" else ":ours"
}
