package `in`.kkkev.jjidea.jj.conflict

/**
 * Which of jj's conflict marker renderings a block uses. See [JjConflictBlockParser]'s class doc
 * for the shape of each. [GIT] and [SNAPSHOT] both parse into an explicit side1/base/side2
 * triple - they differ only in which literal marker (`|||||||` vs `-------`) introduced the base
 * section, which [JjConflictBlockParser] tracks purely to report here, not because the two
 * shapes are handled differently.
 */
enum class ConflictMarkerStyle { GIT, SNAPSHOT, DIFF, UNRECOGNISED }

/** The role jj's own header text assigns to a conflict side, parsed from its trailing `(...)`. */
enum class ConflictRole { DESTINATION, MOVED, BASE }

/**
 * One side (or the base) of a conflict block, as *materialized* content - never the raw
 * `%%%%%%%`/`\\\` diff lines a jj diff-style block may render one side as.
 *
 * @param noTerminatingNewline Whether jj's header annotated this side with `(no terminating
 *   newline)`, meaning its content does not end with `\n` even mid-file. Stripped from [label]
 *   itself (see [JjConflictBlockParser.cleanLabel]) - a caller reconstructing replacement text
 *   for this side (accepting it) must honour this rather than always appending a trailing
 *   newline.
 * @param alternateLabel A second candidate identity for this side, non-null only when this side
 *   was rendered as a `%%%%%%%` diff whose own `"diff from: <label>"` line names a genuine other
 *   side (a [ConflictRole.DESTINATION] or [ConflictRole.MOVED] role) rather than the common
 *   ancestor ([ConflictRole.BASE]). [label] (from the diff's `"to:"` line) is still this side's
 *   primary identity in every case - real jj output always uses `"to:"` for "the actual side",
 *   never `"from:"` (see [JjConflictBlockParser]'s class doc) - but a caller whose primary label
 *   happens to collide with the other side's own label (e.g. both named the same commit due to
 *   divergence) can fall back to this instead of showing two identical labels.
 */
data class ConflictSide(
    val label: String?,
    val role: ConflictRole?,
    val lines: List<String>,
    val noTerminatingNewline: Boolean = false,
    val alternateLabel: String? = null
)

/**
 * One parsed jj conflict marker block, with its document offsets - the per-block, offset-bearing
 * model [JjMarkerConflictExtractor] (whole-file, flattened [ExtractedConflict]) doesn't provide.
 *
 * @param startOffset Offset of the block's opening `<<<<<<<` line's first character.
 * @param endOffset Offset just past the closing `>>>>>>>` line's terminating `\n` - or, when the
 *   block is the text's last line with no trailing newline, the offset of the text's end.
 * @param startLine 0-based index (by `\n`-split lines) of the opening `<<<<<<<` line.
 * @param endLine 0-based index of the closing `>>>>>>>` line.
 * @param side1IsCurrent Whether [side1] (the side `jj resolve --tool :ours` picks) is the side
 *   [JjMarkerConflictExtractor] would place in `MergeData.CURRENT` ("Yours") - see that class's
 *   doc for the GitHub #112 role-based reorientation this mirrors.
 */
data class ConflictBlock(
    val startOffset: Int,
    val endOffset: Int,
    val startLine: Int,
    val endLine: Int,
    val style: ConflictMarkerStyle,
    val side1: ConflictSide,
    val side2: ConflictSide,
    val base: ConflictSide?,
    val side1IsCurrent: Boolean
)
