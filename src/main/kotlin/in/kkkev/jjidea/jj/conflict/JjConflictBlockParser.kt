package `in`.kkkev.jjidea.jj.conflict

/**
 * Shared line-based parser for jj's three conflict marker styles, used by both
 * [JjMarkerConflictExtractor] (whole-file, flattened [ExtractedConflict] for the merge tool) and
 * any caller needing per-block document offsets (the in-editor conflict gutter). All marker
 * literal/regex knowledge lives here - see [JjMarkerConflictExtractor]'s class doc for the
 * GitHub #112 side-orientation background this parser also implements, so it is not duplicated
 * (and cannot drift) across two independent marker-shape implementations.
 *
 * Blocks never nest: a `<<<<<<<` line encountered while already inside an open block is treated
 * as ordinary content (see the `else` branch of [parseBlockFrom]'s line loop), matching real jj
 * output, which never emits nested markers. This is also what makes [ConflictRegionScanner]'s
 * later incremental rescan bound correct - the parser's only cross-line state is a single
 * open/closed bit.
 *
 * Lines are split on `\n` only, matching [String.split] - editor `Document` text is always
 * `\n`-normalized, and jj's own CLI output never emits `\r\n`.
 *
 * **Divergence from [JjMarkerConflictExtractor.extract]:** an *unterminated* trailing block (a
 * `<<<<<<<` with no matching `>>>>>>>` anywhere below it) makes whole-file extraction return
 * null outright - correct for a merge-tool caller that needs a complete, consistent
 * `MergeData`. [parseAll] instead simply omits that trailing open block from its result and
 * stops - correct for an editor scanner, where the user may be mid-edit (e.g. having just typed
 * `<<<<<<<` by hand) and the file's already-closed blocks should still show live regions. Note
 * this omission can only ever affect the *trailing* block: since an unterminated block's search
 * for `>>>>>>>` necessarily scans every remaining line to the true end of the text without
 * finding one, there is provably nothing left afterward for a further block to be found in.
 */
internal object JjConflictBlockParser {
    /** Parses every closed conflict block in [text], in document order. */
    fun parseAll(text: CharSequence): List<ConflictBlock> {
        val table = LineTable(text)
        val blocks = mutableListOf<ConflictBlock>()
        var i = 0
        while (i < table.lineCount) {
            if (table.line(i).startsWith("<<<<<<<")) {
                val openHeader = table.line(i).removePrefix("<<<<<<<").trim()
                val result = parseBlockFrom(table, i + 1, openHeader, blockStartLine = i) ?: break
                blocks += result.block
                i = result.nextLineIndex
            } else {
                i++
            }
        }
        return blocks
    }

    /**
     * Parses exactly one block starting at a `<<<<<<<` line whose first character is at
     * [blockStartOffset]. Returns null if that offset isn't the start of an opening marker line,
     * or the block never closes.
     */
    fun parseBlockAt(text: CharSequence, blockStartOffset: Int): ConflictBlock? {
        val table = LineTable(text)
        val startLine = (0 until table.lineCount).firstOrNull { table.startOffsetOfLine(it) == blockStartOffset }
            ?: return null
        if (!table.line(startLine).startsWith("<<<<<<<")) return null
        val openHeader = table.line(startLine).removePrefix("<<<<<<<").trim()
        return parseBlockFrom(table, startLine + 1, openHeader, blockStartLine = startLine)?.block
    }

    private class ParsedBlock(val block: ConflictBlock, val nextLineIndex: Int)

    private fun parseBlockFrom(
        table: LineTable,
        startIndex: Int,
        openHeader: String,
        blockStartLine: Int
    ): ParsedBlock? {
        var i = startIndex
        val sections = mutableListOf<Section>()
        var kind: Kind? = null
        var header: String? = null
        // Only ever set for a Kind.DIFF section, from its own "%%%%%%% diff from: <label>" line -
        // see Section.diffFromLabel's doc for why this is kept separate from `header` (which the
        // "\\\ to:" line overwrites for the same section).
        var diffFromLabel: String? = null
        val buf = mutableListOf<String>()
        // Collects lines before the first format-specific marker (SIDE1 in bare git-style format).
        val preHeaderBuf = mutableListOf<String>()
        val preHeader = openHeader.takeIf { it.isNotBlank() }
        var sawPipeBase = false
        var sawDashBase = false
        var closeLine = -1

        fun flush(newKind: Kind?, newHeader: String?, newDiffFromLabel: String? = null) {
            kind?.let { sections.add(Section(it, buf.toList(), header, diffFromLabel)) }
                ?: run {
                    if (preHeaderBuf.isNotEmpty()) sections.add(Section(Kind.SIDE1, preHeaderBuf.toList(), preHeader))
                }
            buf.clear()
            kind = newKind
            header = newHeader
            diffFromLabel = newDiffFromLabel
        }

        while (i < table.lineCount) {
            val line = table.line(i)
            when {
                line.startsWith(">>>>>>>") -> {
                    // Git style's closing marker carries side #2's own label (there's no text on
                    // the preceding "=======" line to source it from).
                    if (kind == Kind.SIDE2 && header == null) {
                        header = line.removePrefix(">>>>>>>").trim().takeIf { it.isNotBlank() }
                    }
                    kind?.let { sections.add(Section(it, buf.toList(), header, diffFromLabel)) }
                        ?: run {
                            if (preHeaderBuf.isNotEmpty()) {
                                sections.add(Section(Kind.SIDE1, preHeaderBuf.toList(), preHeader))
                            }
                        }
                    closeLine = i
                    i++
                    break
                }
                // Git conflict style (jj 0.28+, also used with `ui.conflict-marker-style = "git"`):
                //   <<<<<<< side1 info
                //   ... side1 content ...
                //   ||||||| base info
                //   ... base content ...
                //   =======
                //   ... side2 content ...
                //   >>>>>>> side2 info
                line.startsWith("|||||||") -> {
                    sawPipeBase = true
                    flush(Kind.BASE, line.removePrefix("|||||||").trim().takeIf { it.isNotBlank() })
                }
                line == "=======" && (kind == null || kind == Kind.BASE) -> {
                    if (kind == null) {
                        if (preHeaderBuf.isNotEmpty()) {
                            sections.add(Section(Kind.SIDE1, preHeaderBuf.toList(), preHeader))
                        }
                    } else {
                        sections.add(Section(Kind.BASE, buf.toList(), header, diffFromLabel))
                    }
                    buf.clear()
                    kind = Kind.SIDE2
                    header = null // filled in from the closing ">>>>>>>" line, above
                    diffFromLabel = null
                }
                // Old/snapshot conflict style (+++++++/-------) and diff style (%%%%%%%):
                //   <<<<<<< Conflict N of M
                //   +++++++ Contents of side #1
                //   ...
                //   ------- base
                //   ...
                //   +++++++ Contents of side #2
                //   ...
                //   >>>>>>>
                line.startsWith("+++++++") -> {
                    val text = line.removePrefix("+++++++").trim()
                    val newKind = when {
                        text.contains("Contents of side #1") -> Kind.SIDE1
                        text.contains("Contents of side #2") -> Kind.SIDE2
                        else -> Kind.CONTENT
                    }
                    // Snapshot style's "Contents of side #N" boilerplate carries no commit identity.
                    flush(newKind, text.takeIf { newKind == Kind.CONTENT })
                }
                line.startsWith("-------") -> {
                    sawDashBase = true
                    flush(Kind.BASE, null)
                }
                line.startsWith("%%%%%%%") -> {
                    // Primary label comes from the "\\\ to:" line below - this line's own "from:"
                    // text is only ever a fallback (Section.diffFromLabel's doc explains why).
                    val fromLabel = line.removePrefix("%%%%%%%").trim().removePrefix("diff from:").trim()
                        .takeIf { it.isNotBlank() }
                    flush(Kind.DIFF, null, fromLabel)
                }
                // The diff-section header continuation line - "\\\\\\\        to: <label>" - names
                // that side, not the base ("%%%%%%% diff from: <label>" names the base instead).
                line.startsWith("\\\\\\") -> {
                    if (kind == Kind.DIFF) {
                        line.trimStart('\\').trim().removePrefix("to:").trim()
                            .takeIf { it.isNotBlank() }?.let { header = it }
                    }
                }
                else -> kind?.let { buf.add(line) } ?: preHeaderBuf.add(line)
            }
            i++
        }

        if (closeLine < 0) return null

        val block = buildBlock(sections, table, blockStartLine, closeLine, sawPipeBase, sawDashBase) ?: return null
        return ParsedBlock(block, i)
    }

    private fun buildBlock(
        sections: List<Section>,
        table: LineTable,
        startLine: Int,
        closeLine: Int,
        sawPipeBase: Boolean,
        sawDashBase: Boolean
    ): ConflictBlock? {
        val startOffset = table.startOffsetOfLine(startLine)
        val endOffset = table.endOffsetOfLine(closeLine)

        // Git format (with base) and snapshot format both parse to an explicit side1/base/side2
        // triple - they differ only in which marker introduced the base section.
        val side1Section = sections.find { it.kind == Kind.SIDE1 }
        val baseSection = sections.find { it.kind == Kind.BASE }
        val side2Section = sections.find { it.kind == Kind.SIDE2 }
        if (side1Section != null && side2Section != null) {
            val style = if (sawPipeBase) {
                ConflictMarkerStyle.GIT
            } else if (sawDashBase) {
                ConflictMarkerStyle.SNAPSHOT
            } else {
                ConflictMarkerStyle.GIT
            }
            return explicitSidesBlock(
                side1Section,
                baseSection,
                side2Section,
                style,
                startOffset,
                endOffset,
                startLine,
                closeLine
            )
        }

        // Diff format: two non-base sections, in file order - one full content (+++++++), one a
        // diff from base (%%%%%%%/\\\\\\\). Whichever appears first is side #1: jj may render
        // either role as content or as a diff, so this must not assume +++++++ is always side #1
        // (see class doc).
        val nonBase = sections.filter { it.kind == Kind.CONTENT || it.kind == Kind.DIFF }
        if (nonBase.size == 2) {
            val (first, second) = nonBase
            val (firstContent, firstBase) = materialize(first)
            val (secondContent, secondBase) = materialize(second)
            val baseLines = if (first.kind == Kind.DIFF) firstBase else secondBase
            val label1 = cleanLabel(first.header)
            val label2 = cleanLabel(second.header)
            val side1 = ConflictSide(
                label1?.label,
                roleOf(label1?.label),
                firstContent,
                label1?.noTerminatingNewline ?: false,
                alternateLabel(first)
            )
            val side2 = ConflictSide(
                label2?.label,
                roleOf(label2?.label),
                secondContent,
                label2?.noTerminatingNewline ?: false,
                alternateLabel(second)
            )
            return ConflictBlock(
                startOffset = startOffset,
                endOffset = endOffset,
                startLine = startLine,
                endLine = closeLine,
                style = ConflictMarkerStyle.DIFF,
                side1 = side1,
                side2 = side2,
                base = if (baseLines.isNotEmpty()) ConflictSide(null, ConflictRole.BASE, baseLines) else null,
                side1IsCurrent = currentIsSide1(side1.role, side2.role)
            )
        }

        // Fallback: treat collected sections as side1/side2, no labels (malformed/unrecognised shape)
        val contents = sections.map { it.lines }
        return ConflictBlock(
            startOffset = startOffset,
            endOffset = endOffset,
            startLine = startLine,
            endLine = closeLine,
            style = ConflictMarkerStyle.UNRECOGNISED,
            side1 = ConflictSide(null, null, contents.firstOrNull() ?: emptyList()),
            side2 = ConflictSide(null, null, contents.lastOrNull() ?: emptyList()),
            base = null,
            side1IsCurrent = true
        )
    }

    private fun explicitSidesBlock(
        side1: Section,
        baseSection: Section?,
        side2: Section,
        style: ConflictMarkerStyle,
        startOffset: Int,
        endOffset: Int,
        startLine: Int,
        endLine: Int
    ): ConflictBlock {
        val label1 = cleanLabel(side1.header)
        val label2 = cleanLabel(side2.header)
        val s1 = ConflictSide(label1?.label, roleOf(label1?.label), side1.lines, label1?.noTerminatingNewline ?: false)
        val s2 = ConflictSide(label2?.label, roleOf(label2?.label), side2.lines, label2?.noTerminatingNewline ?: false)
        val baseLabel = baseSection?.let { cleanLabel(it.header) }
        return ConflictBlock(
            startOffset = startOffset,
            endOffset = endOffset,
            startLine = startLine,
            endLine = endLine,
            style = style,
            side1 = s1,
            side2 = s2,
            base = baseSection?.let {
                ConflictSide(
                    baseLabel?.label,
                    ConflictRole.BASE,
                    it.lines,
                    baseLabel?.noTerminatingNewline ?: false
                )
            },
            side1IsCurrent = currentIsSide1(s1.role, s2.role)
        )
    }

    private fun currentIsSide1(role1: ConflictRole?, role2: ConflictRole?): Boolean = when {
        role1 == ConflictRole.MOVED && role2 == ConflictRole.DESTINATION -> true
        role1 == ConflictRole.DESTINATION && role2 == ConflictRole.MOVED -> false
        else -> true
    }

    private enum class Kind { SIDE1, BASE, SIDE2, DIFF, CONTENT }

    private data class Section(
        val kind: Kind,
        val lines: List<String>,
        val header: String?,
        val diffFromLabel: String? = null
    )

    /** Splits a `%%%%%%%` unified-diff section into (this side's content, its contribution to the base). */
    private fun materialize(section: Section): Pair<List<String>, List<String>> = when (section.kind) {
        Kind.CONTENT -> section.lines to emptyList()
        else -> {
            val content = mutableListOf<String>()
            val base = mutableListOf<String>()
            for (line in section.lines) {
                when {
                    line.startsWith("+") -> content.add(line.substring(1))
                    line.startsWith("-") -> base.add(line.substring(1))
                    else -> {
                        content.add(line)
                        base.add(line)
                    }
                }
            }
            content to base
        }
    }

    private data class CleanedLabel(val label: String, val noTerminatingNewline: Boolean)

    private val noTerminatingNewlineSuffix = " (no terminating newline)"
    private val boilerplateHeader = Regex("""conflict \d+ of \d+( ends)?""", RegexOption.IGNORE_CASE)
    private val destinationRole = Regex(""".*\((?:[a-z]+ destination|new parents)\)\s*$""", RegexOption.IGNORE_CASE)
    private val movedRole = Regex(""".*\((?!parents of )[a-z]+ed revision\)\s*$""", RegexOption.IGNORE_CASE)
    private val baseRole = Regex(""".*\(parents of [a-z]+ revision\)\s*$""", RegexOption.IGNORE_CASE)

    private fun cleanLabel(raw: String?): CleanedLabel? {
        val hadSuffix = raw?.endsWith(noTerminatingNewlineSuffix) == true
        val trimmed = raw?.removeSuffix(noTerminatingNewlineSuffix)?.trim()
        if (trimmed.isNullOrBlank()) return null
        if (boilerplateHeader.matches(trimmed)) return null
        return CleanedLabel(trimmed, hadSuffix)
    }

    private fun roleOf(label: String?): ConflictRole? = label?.let {
        when {
            destinationRole.matches(it) -> ConflictRole.DESTINATION
            movedRole.matches(it) -> ConflictRole.MOVED
            baseRole.matches(it) -> ConflictRole.BASE
            else -> null
        }
    }

    /**
     * [Section.diffFromLabel], cleaned, but only when its role is a genuine side
     * ([ConflictRole.DESTINATION] or [ConflictRole.MOVED]) rather than the common ancestor
     * ([ConflictRole.BASE], real jj output's normal case - see [ConflictSide.alternateLabel]'s
     * doc). Null for anything but a [Kind.DIFF] section, or when the "from:" line named no role
     * at all (nothing to gain over the primary [label]).
     */
    private fun alternateLabel(section: Section): String? {
        if (section.kind != Kind.DIFF) return null
        val cleaned = cleanLabel(section.diffFromLabel) ?: return null
        return cleaned.label.takeIf { roleOf(it).let { role -> role == ConflictRole.DESTINATION || role == ConflictRole.MOVED } }
    }

    /** `\n`-split line access with precomputed line-start offsets, built once per parse call. */
    private class LineTable(text: CharSequence) {
        private val lines: List<String> = text.toString().split('\n')
        private val offsets: IntArray = IntArray(lines.size).also { arr ->
            var offset = 0
            for (i in lines.indices) {
                arr[i] = offset
                offset += lines[i].length + 1
            }
        }

        val lineCount get() = lines.size
        fun line(i: Int): String = lines[i]
        fun startOffsetOfLine(i: Int): Int = offsets[i]
        fun endOffsetOfLine(i: Int): Int = if (i + 1 < lines.size) offsets[i + 1] else offsets[i] + lines[i].length
    }
}
