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
 * @param isDiffSection True only for the side rendered as a `%%%%%%%` unified-diff section, whose
 *   raw span carries `-`/`+` line prefixes (jj-idea-8u0g).
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
 * @param contentStartOffset The start of [contentEndOffset]'s span - see that param's doc.
 * @param contentEndOffset This side's own **raw** span in the document, together with
 *   [contentStartOffset] - the literal text between its own marker line and the next
 *   marker/close line (verbatim, including e.g. a `DIFF`-style section's own `+`/`-` prefixes -
 *   never the *materialized* text [lines] holds). Both `null` together only when there is no
 *   such contiguous span to point at: the `UNRECOGNISED` fallback shape, or a `DIFF`-style
 *   [ConflictBlock.base] (synthesized from `-` lines scattered across the diff section, not read
 *   from one literal place - see `choicesFor`'s matching exclusion of `DIFF` bases in
 *   `conflictBlockReplacement.kt`). Always both non-null otherwise, even when the span is empty
 *   (e.g. an explicit but empty `|||||||`/`=======` base section) - `contentStartOffset ==
 *   contentEndOffset` then. Added for the in-editor gutter's per-side background highlighting
 *   (jj-idea-82fo follow-up); the accept logic in `conflictBlockReplacement.kt` doesn't need
 *   these, since it replaces the *whole* block from [lines], not this side's own raw span.
 */
data class ConflictSide(
    val label: String?,
    val role: ConflictRole?,
    val lines: List<String>,
    val noTerminatingNewline: Boolean = false,
    val alternateLabel: String? = null,
    val contentStartOffset: Int? = null,
    val contentEndOffset: Int? = null,
    val isDiffSection: Boolean = false
)

/**
 * A run of consecutive marker lines (`<<<<<<<`, `|||||||`, `=======`, `%%%%%%%`+`\\\\`, ...)
 * inside one [ConflictBlock], for the gutter's divider folds (jj-idea-6ja9).
 *
 * @param next The side whose content follows this run; null for the closing `>>>>>>>` run, or
 *   when no section starts right after it.
 * @param label That side's cleaned label, when it has one.
 */
data class MarkerRun(
    val startOffset: Int,
    val endOffset: Int,
    val startLine: Int,
    val endLine: Int,
    val next: AcceptChoice?,
    val label: String?
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
 * @param markerRuns Marker-line runs in document order; empty for [ConflictMarkerStyle.UNRECOGNISED].
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
    val side1IsCurrent: Boolean,
    val markerRuns: List<MarkerRun> = emptyList()
)
