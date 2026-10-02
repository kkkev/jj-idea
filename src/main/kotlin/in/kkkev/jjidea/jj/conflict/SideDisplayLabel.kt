package `in`.kkkev.jjidea.jj.conflict

/**
 * The display labels for *both* sides of a conflict together, across one or more conflicted
 * files, shared by [in.kkkev.jjidea.ui.editor.conflictBannerModel] (always exactly one file) and
 * the bulk "Accept …" actions ([in.kkkev.jjidea.actions.file.AcceptConflictSideAction], any
 * number of selected files).
 *
 * Deliberately computed **atomically**: either both sides get a confident, specific label, or
 * both fall back to the generic wording (e.g. "Side #1"/"Side #2") together - never one specific
 * label alongside a generic one for the other side. A real reported case shows why a
 * per-side-independent decision is misleading: two files that both name commits "A" and "B", but
 * assign them to *opposite* CURRENT/LAST slots (one file's CURRENT is "A", the other's CURRENT is
 * "B") can still coincidentally agree on *one* slot's raw text while genuinely disagreeing on the
 * other - showing a specific label for the slot that happened to agree, and a generic one for the
 * slot that didn't, asserts a false consistency the selection doesn't actually have.
 *
 * The one exception is a selection that is really "the same two commits, reordered" (`jj-idea-0k7k`):
 * when the joint rule above can't resolve, but every file *individually* resolves to a pair of
 * distinct labels and those pairs are all the same pair or its exact swap, the first file's
 * order is used and [SideDisplayLabels.swapped] marks the files whose CURRENT/LAST are the other
 * way round. Callers must then accept the opposite side in those files, or the label would lie.
 *
 * Each side's own candidate (see [resolvedOrNull]) follows the same tiers, most specific first:
 * 1. Every file's title for this side agrees on the same non-null value, and that value differs
 *    from the other side's own shared value - jj's own literal claim (e.g. a diff section's
 *    `"to:"` text), taken at face value. Deliberately does *not* match on a weaker signal like a
 *    shared role word alone (e.g. two unrelated rebases both saying "(rebased revision)"), since
 *    that would coerce genuinely unrelated conflicts into a false-looking single label.
 * 2. When tier 1 is missing or collides with the other side's, try [ConflictSide.alternateLabel]'s
 *    counterpart on [ExtractedConflict] the same way (every file must agree on the same non-null
 *    alternate) - jj's own text for a second, genuinely different commit that would otherwise be
 *    silently discarded (see that field's doc).
 * 3. This side has no usable alternate, but the other side does (same "every file agrees" rule) -
 *    use this side's tier-1 value after all. The other side is the one whose primary label was
 *    actually unreliable (that's exactly why it has an alternate); once it moves off that label,
 *    there is no real collision left for this side to be worried about.
 * 4. `null` - nothing above applies, so [sideDisplayLabels] falls back for *both* sides.
 */
fun sideDisplayLabels(
    currentTitles: List<String?>,
    currentAlternateTitles: List<String?>,
    lastTitles: List<String?>,
    lastAlternateTitles: List<String?>,
    currentFallback: String,
    lastFallback: String
): SideDisplayLabels {
    val unswapped = List(currentTitles.size) { false }
    val current = resolvedOrNull(currentTitles, currentAlternateTitles, lastTitles, lastAlternateTitles)
    val last = resolvedOrNull(lastTitles, lastAlternateTitles, currentTitles, currentAlternateTitles)
    if (current != null && last != null) return SideDisplayLabels(current, last, unswapped)
    return swappedPairOrNull(currentTitles, currentAlternateTitles, lastTitles, lastAlternateTitles)
        ?: SideDisplayLabels(currentFallback, lastFallback, unswapped)
}

/**
 * Both sides' labels plus, per input file, whether that file's CURRENT holds the commit shown
 * under [last] (and its LAST the one under [current]) - see [sideDisplayLabels].
 */
data class SideDisplayLabels(val current: String, val last: String, val swapped: List<Boolean>)

/** The "same two commits, reordered" case - see [sideDisplayLabels]'s doc. O(n) over files. */
private fun swappedPairOrNull(
    currentTitles: List<String?>,
    currentAlternateTitles: List<String?>,
    lastTitles: List<String?>,
    lastAlternateTitles: List<String?>
): SideDisplayLabels? {
    val pairs = currentTitles.indices.map { i ->
        val cur = resolvedOrNull(
            listOf(currentTitles[i]),
            listOf(currentAlternateTitles[i]),
            listOf(lastTitles[i]),
            listOf(lastAlternateTitles[i])
        )
        val last = resolvedOrNull(
            listOf(lastTitles[i]),
            listOf(lastAlternateTitles[i]),
            listOf(currentTitles[i]),
            listOf(currentAlternateTitles[i])
        )
        if (cur == null || last == null || cur == last) return null
        cur to last
    }
    val (refCurrent, refLast) = pairs.firstOrNull() ?: return null
    val swapped = pairs.map { (cur, last) ->
        when {
            cur == refCurrent && last == refLast -> false
            cur == refLast && last == refCurrent -> true
            else -> return null
        }
    }
    return SideDisplayLabels(refCurrent, refLast, swapped)
}

/** One side's own tiers 1-3 - see [sideDisplayLabels]'s doc. `null` means "fall back". */
private fun resolvedOrNull(
    titles: List<String?>,
    alternateTitles: List<String?>,
    otherSideTitles: List<String?>,
    otherSideAlternateTitles: List<String?>
): String? {
    val shared = titles.distinct().singleOrNull()
    val otherShared = otherSideTitles.distinct().singleOrNull()
    if (shared != null && shared != otherShared) return shared

    val sharedAlternate = alternateTitles.distinct().singleOrNull()
    if (sharedAlternate != null && sharedAlternate != otherShared) return sharedAlternate

    if (shared != null && otherSideAlternateTitles.distinct().singleOrNull() != null) return shared

    return null
}
