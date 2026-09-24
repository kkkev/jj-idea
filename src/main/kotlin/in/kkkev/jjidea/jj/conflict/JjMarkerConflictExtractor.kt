package `in`.kkkev.jjidea.jj.conflict

import com.intellij.openapi.vcs.merge.MergeData

/**
 * ### GitHub #112: which side is "Yours"?
 *
 * jj embeds a commit description and a role annotation in every marker header, e.g.
 * `+++++++ ulmlywnv c280fd5d "my change" (rebased revision)`. For a rebase conflict this names
 * one side `"(rebase destination)"` (the commit rebased onto) and the other
 * `"(rebased revision)"` (the user's own moved commit) - see [JjConflictBlockParser]'s private
 * `roleOf` for the full vocabulary, verified against real jj 0.44 output and jj's own embedded
 * docs.
 *
 * Which of those two roles jj renders as full content (`+++++++`) versus as a diff from the
 * base (`%%%%%%%`/`\\\\\\\`) is a materialization choice, not a fixed convention - real jj 0.44
 * output puts the rebase destination first as `+++++++`, while jj's own bundled documentation
 * shows the opposite layout for the same kind of conflict (destination as the `%%%%%%%` diff,
 * the rebased revision as `+++++++`). A parser that assumes "`+++++++` is always side #1" -
 * this class's previous behaviour - therefore assigns [MergeData.CURRENT] to whichever role
 * jj happened to render as content, which is exactly the "sometimes correct" swap reported in
 * GitHub #112.
 *
 * The fix: identify side #1/#2 by *file order* (whichever section appears first is side #1,
 * mirroring how git-style markers unambiguously use `<<<<<<<`/`>>>>>>>` position), then, only
 * when a block's two sides carry recognisably opposite roles (one "destination", one "moved"),
 * reorient so the moved/rebased side lands in `CURRENT` ("Yours") regardless of which position
 * it started in. A conflict with no role text (plain merges, squashes, snapshot-style markers)
 * is completely unaffected - there is nothing to key the reorientation off, so it keeps today's
 * side #1 -> `CURRENT` mapping, which is exactly right for those (non-asymmetric) conflicts.
 */
class JjMarkerConflictExtractor : ConflictExtractor {
    override fun extract(fileContent: ByteArray): ExtractedConflict? {
        val text = fileContent.toString(Charsets.UTF_8)
        val blocks = JjConflictBlockParser.parseAll(text)
        if (blocks.isEmpty()) return null

        // JjConflictBlockParser.parseAll silently drops a trailing unterminated block (see its
        // class doc) - a merge-tool caller needs a complete, consistent MergeData, so any opening
        // marker that didn't turn into a parsed block means the whole extraction must fail, not
        // just the blocks after it. Every parsed block consumes exactly one "<<<<<<<" line, so a
        // mismatched count means at least one opener never closed.
        if (blocks.size != countOpenMarkerLines(text)) return null

        // Only reorient if every block in the file agrees on which role belongs in CURRENT -
        // a file's blocks come from a single operation, so in practice they always agree; a
        // disagreement (or a block with no role at all) falls back to the pre-existing
        // side #1 -> CURRENT mapping rather than guessing.
        val opinions = blocks.map { it.side1IsCurrent }.distinct()
        val currentIsSide1 = opinions.singleOrNull() ?: true

        val lines = text.split('\n')
        val current = mutableListOf<String>()
        val original = mutableListOf<String>()
        val last = mutableListOf<String>()

        var lineIndex = 0
        for (block in blocks) {
            while (lineIndex < block.startLine) {
                current += lines[lineIndex]
                original += lines[lineIndex]
                last += lines[lineIndex]
                lineIndex++
            }
            if (currentIsSide1) {
                current += block.side1.lines
                last += block.side2.lines
            } else {
                current += block.side2.lines
                last += block.side1.lines
            }
            original += block.base?.lines ?: emptyList()
            lineIndex = block.endLine + 1
        }
        while (lineIndex < lines.size) {
            current += lines[lineIndex]
            original += lines[lineIndex]
            last += lines[lineIndex]
            lineIndex++
        }

        val firstBlock = blocks.first()
        val (currentTitle, lastTitle) = if (currentIsSide1) {
            firstBlock.side1.label to firstBlock.side2.label
        } else {
            firstBlock.side2.label to firstBlock.side1.label
        }
        val (currentAlternateTitle, lastAlternateTitle) = if (currentIsSide1) {
            firstBlock.side1.alternateLabel to firstBlock.side2.alternateLabel
        } else {
            firstBlock.side2.alternateLabel to firstBlock.side1.alternateLabel
        }

        return ExtractedConflict(
            mergeData = MergeData().also {
                it.CURRENT = current.joinToString("\n").toByteArray(Charsets.UTF_8)
                it.ORIGINAL = original.joinToString("\n").toByteArray(Charsets.UTF_8)
                it.LAST = last.joinToString("\n").toByteArray(Charsets.UTF_8)
            },
            currentTitle = currentTitle,
            currentAlternateTitle = currentAlternateTitle,
            lastAlternateTitle = lastAlternateTitle,
            lastTitle = lastTitle,
            currentIsJjSide1 = currentIsSide1
        )
    }

    private fun countOpenMarkerLines(text: CharSequence): Int =
        text.split('\n').count { it.startsWith("<<<<<<<") }
}
