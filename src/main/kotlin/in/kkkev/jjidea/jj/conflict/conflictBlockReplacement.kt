package `in`.kkkev.jjidea.jj.conflict

/**
 * The four ways a [ConflictBlock] can be accepted (jj-idea-82fo, stage 4/4) - each a pure
 * document edit, never a jj command: accepting a block replaces its `<<<<<<<`...`>>>>>>>` text
 * with the chosen side's content, and jj simplifies the conflict from whatever markers remain on
 * the next snapshot. [BASE] is only ever offered for a block that actually has one (see
 * [choicesFor]).
 */
enum class AcceptChoice { SIDE1, SIDE2, BOTH, BASE }

/**
 * The [AcceptChoice]s meaningful for [block] - always [AcceptChoice.SIDE1]/[AcceptChoice.SIDE2]/
 * [AcceptChoice.BOTH], plus [AcceptChoice.BASE] only when [block] has an explicit base section
 * ([ConflictMarkerStyle.GIT]'s `|||||||` or [ConflictMarkerStyle.SNAPSHOT]'s `-------`).
 *
 * [ConflictMarkerStyle.DIFF]'s [ConflictBlock.base] is *derived* (materialized from a
 * `%%%%%%%`/`\\\` unified-diff section, not read verbatim from an explicit base section - see
 * [JjConflictBlockParser]'s class doc) - deliberately excluded here even when non-null, since
 * shipping a possibly-wrong derived base as an "Accept Base" action risks silently replacing the
 * block with content the user never actually saw written out anywhere. Revisit as a follow-on
 * once that derivation has more real-world mileage.
 */
fun choicesFor(block: ConflictBlock): List<AcceptChoice> = buildList {
    add(AcceptChoice.SIDE1)
    add(AcceptChoice.SIDE2)
    add(AcceptChoice.BOTH)
    if (block.base != null && block.style != ConflictMarkerStyle.DIFF) add(AcceptChoice.BASE)
}

/**
 * The replacement text for [block]'s whole `<<<<<<<`...`>>>>>>>` span (start to end offset
 * inclusive of both marker lines) if [choice] is accepted - the caller replaces exactly that
 * span with this string. Never itself touches a [com.intellij.openapi.editor.Document]; see
 * [in.kkkev.jjidea.ui.editor.conflict.AcceptConflictBlockAction] for the write.
 *
 * - [AcceptChoice.SIDE1]/[AcceptChoice.SIDE2]/[AcceptChoice.BASE]: that side's lines alone,
 *   honouring [ConflictSide.noTerminatingNewline] (mid-file this is always false, since another
 *   line follows; only a block sitting at the true end of the document can have it set).
 * - [AcceptChoice.BOTH]: side #1's lines, then side #2's lines, with no separator between them -
 *   side #1 always gets a trailing newline here regardless of its own
 *   [ConflictSide.noTerminatingNewline] (side #2's content follows immediately, so it is never
 *   really "unterminated" in this combination); only side #2's own flag governs the very end of
 *   the result. An empty side contributes nothing (not even a blank line).
 *
 * @throws IllegalArgumentException if [choice] is [AcceptChoice.BASE] but [block] has none - a
 *   caller should always consult [choicesFor] first, exactly as
 *   [in.kkkev.jjidea.ui.editor.conflict.ConflictBlockGutterIconRenderer] does when building its
 *   menu.
 */
fun replacementFor(block: ConflictBlock, choice: AcceptChoice): String = when (choice) {
    AcceptChoice.SIDE1 -> sideText(block.side1)
    AcceptChoice.SIDE2 -> sideText(block.side2)
    AcceptChoice.BASE -> sideText(
        requireNotNull(block.base) { "replacementFor(BASE) called on a block with no base section" }
    )
    AcceptChoice.BOTH -> buildString {
        if (block.side1.lines.isNotEmpty()) append(block.side1.lines.joinToString("\n")).append("\n")
        append(sideText(block.side2))
    }
}

private fun sideText(side: ConflictSide): String =
    if (side.lines.isEmpty()) "" else side.lines.joinToString("\n") + if (side.noTerminatingNewline) "" else "\n"
