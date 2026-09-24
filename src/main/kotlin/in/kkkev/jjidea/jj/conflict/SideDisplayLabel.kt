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
 * `jj-idea-0k7k` (tracked separately): detecting that such a selection is really "the same two
 * commits, reordered" and presenting a coherent swapped pair instead of falling back - deferred as
 * a larger, riskier heuristic; this atomic all-or-nothing rule is the safe default until then.
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
): Pair<String, String> {
    val current = resolvedOrNull(currentTitles, currentAlternateTitles, lastTitles, lastAlternateTitles)
    val last = resolvedOrNull(lastTitles, lastAlternateTitles, currentTitles, currentAlternateTitles)
    return if (current != null && last != null) current to last else currentFallback to lastFallback
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
