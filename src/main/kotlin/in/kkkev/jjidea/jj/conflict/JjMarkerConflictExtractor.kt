package `in`.kkkev.jjidea.jj.conflict

import com.intellij.openapi.vcs.merge.MergeData

/**
 * ### GitHub #112: which side is "Yours"?
 *
 * jj embeds a commit description and a role annotation in every marker header, e.g.
 * `+++++++ ulmlywnv c280fd5d "my change" (rebased revision)`. For a rebase conflict this names
 * one side `"(rebase destination)"` (the commit rebased onto) and the other
 * `"(rebased revision)"` (the user's own moved commit) - see [roleOf] for the full vocabulary,
 * verified against real jj 0.44 output and jj's own embedded docs.
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
        val lines = fileContent.toString(Charsets.UTF_8).split('\n')
        val segments = mutableListOf<Segment>()
        var foundAnyConflict = false
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("<<<<<<<")) {
                foundAnyConflict = true
                val openHeader = line.removePrefix("<<<<<<<").trim()
                i++
                val block = parseBlock(lines, i, openHeader) ?: return null
                i = block.nextIndex
                segments += Segment.Conflict(block)
            } else {
                segments += Segment.Plain(line)
                i++
            }
        }

        if (!foundAnyConflict) return null

        // Only reorient if every block in the file agrees on which role belongs in CURRENT -
        // a file's blocks come from a single operation, so in practice they always agree; a
        // disagreement (or a block with no role at all) falls back to the pre-existing
        // side #1 -> CURRENT mapping rather than guessing.
        val blocks = segments.filterIsInstance<Segment.Conflict>().map { it.block }
        val opinions = blocks.map { it.currentIsSide1 }.distinct()
        val currentIsSide1 = opinions.singleOrNull() ?: true

        val current = mutableListOf<String>()
        val original = mutableListOf<String>()
        val last = mutableListOf<String>()
        for (segment in segments) {
            when (segment) {
                is Segment.Plain -> {
                    current += segment.line
                    original += segment.line
                    last += segment.line
                }

                is Segment.Conflict -> {
                    val block = segment.block
                    if (currentIsSide1) {
                        current += block.side1
                        last += block.side2
                    } else {
                        current += block.side2
                        last += block.side1
                    }
                    original += block.original
                }
            }
        }

        val firstBlock = blocks.first()
        val (currentTitle, lastTitle) = if (currentIsSide1) {
            firstBlock.label1 to firstBlock.label2
        } else {
            firstBlock.label2 to firstBlock.label1
        }

        return ExtractedConflict(
            mergeData = MergeData().also {
                it.CURRENT = current.joinToString("\n").toByteArray(Charsets.UTF_8)
                it.ORIGINAL = original.joinToString("\n").toByteArray(Charsets.UTF_8)
                it.LAST = last.joinToString("\n").toByteArray(Charsets.UTF_8)
            },
            currentTitle = currentTitle,
            lastTitle = lastTitle,
            currentIsJjSide1 = currentIsSide1
        )
    }

    private sealed class Segment {
        data class Plain(val line: String) : Segment()
        data class Conflict(val block: Block) : Segment()
    }

    /**
     * @param side1 Content of jj's conflict side #1 - whichever non-base section appears first
     *   in the file. This is what `jj resolve --tool :ours` picks.
     * @param side2 Content of jj's conflict side #2 (`:theirs`) - the second non-base section.
     * @param currentIsSide1 Whether [side1] (rather than [side2]) belongs in `CURRENT`. `true`
     *   unless [role1]/[role2] identify one side as the rebase-style destination and the other
     *   as the moved/rebased revision, in which case the moved side is what "Yours" should mean.
     */
    private data class Block(
        val side1: List<String>,
        val original: List<String>,
        val side2: List<String>,
        val nextIndex: Int,
        val label1: String?,
        val label2: String?,
        val role1: Role?,
        val role2: Role?
    ) {
        val currentIsSide1: Boolean
            get() = when {
                role1 == Role.MOVED && role2 == Role.DESTINATION -> true
                role1 == Role.DESTINATION && role2 == Role.MOVED -> false
                else -> true
            }
    }

    /** The role jj's own header text assigns to a conflict side, parsed from its trailing `(...)`. */
    private enum class Role { DESTINATION, MOVED, BASE }

    private enum class Kind { SIDE1, BASE, SIDE2, DIFF, CONTENT }

    private data class Section(val kind: Kind, val lines: List<String>, val header: String?)

    private fun parseBlock(lines: List<String>, startIndex: Int, openHeader: String): Block? {
        var i = startIndex
        val sections = mutableListOf<Section>()
        var kind: Kind? = null
        var header: String? = null
        val buf = mutableListOf<String>()
        // Collects lines before the first format-specific marker (SIDE1 in git-style format)
        val preHeaderBuf = mutableListOf<String>()
        val preHeader = openHeader.takeIf { it.isNotBlank() }
        var closed = false

        fun flush(newKind: Kind?, newHeader: String?) {
            kind?.let { sections.add(Section(it, buf.toList(), header)) }
                ?: run {
                    if (preHeaderBuf.isNotEmpty()) sections.add(Section(Kind.SIDE1, preHeaderBuf.toList(), preHeader))
                }
            buf.clear()
            kind = newKind
            header = newHeader
        }

        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith(">>>>>>>") -> {
                    // Git style's closing marker carries side #2's own label (there's no text on
                    // the preceding "=======" line to source it from).
                    if (kind == Kind.SIDE2 && header == null) {
                        header = line.removePrefix(">>>>>>>").trim().takeIf { it.isNotBlank() }
                    }
                    kind?.let { sections.add(Section(it, buf.toList(), header)) }
                        ?: run {
                            if (preHeaderBuf.isNotEmpty()) {
                                sections.add(
                                    Section(Kind.SIDE1, preHeaderBuf.toList(), preHeader)
                                )
                            }
                        }
                    closed = true
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
                line.startsWith("|||||||") -> flush(
                    Kind.BASE,
                    line.removePrefix("|||||||").trim().takeIf {
                        it.isNotBlank()
                    }
                )
                line == "=======" && (kind == null || kind == Kind.BASE) -> {
                    if (kind == null) {
                        if (preHeaderBuf.isNotEmpty()) {
                            sections.add(
                                Section(Kind.SIDE1, preHeaderBuf.toList(), preHeader)
                            )
                        }
                    } else {
                        sections.add(Section(Kind.BASE, buf.toList(), header))
                    }
                    buf.clear()
                    kind = Kind.SIDE2
                    header = null // filled in from the closing ">>>>>>>" line, above
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
                line.startsWith("-------") -> flush(Kind.BASE, null)
                line.startsWith("%%%%%%%") -> flush(Kind.DIFF, null) // label comes from the "\\\ to:" line below
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

        if (!closed) return null

        // Git format (with base): side1 + base + side2
        val side1Section = sections.find { it.kind == Kind.SIDE1 }
        val baseSection = sections.find { it.kind == Kind.BASE }
        val side2Section = sections.find { it.kind == Kind.SIDE2 }
        if (side1Section != null && side2Section != null) {
            return block(side1Section, baseSection?.lines ?: emptyList(), side2Section, i)
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
            val original = if (first.kind == Kind.DIFF) firstBase else secondBase
            return Block(
                side1 = firstContent,
                original = original,
                side2 = secondContent,
                nextIndex = i,
                label1 = cleanLabel(first.header),
                label2 = cleanLabel(second.header),
                role1 = roleOf(cleanLabel(first.header)),
                role2 = roleOf(cleanLabel(second.header))
            )
        }

        // Fallback: treat collected sections as side1/side2, no labels (malformed/unrecognised shape)
        val contents = sections.map { it.lines }
        return Block(
            side1 = contents.firstOrNull() ?: emptyList(),
            original = emptyList(),
            side2 = contents.lastOrNull() ?: emptyList(),
            nextIndex = i,
            label1 = null,
            label2 = null,
            role1 = null,
            role2 = null
        )
    }

    private fun block(side1: Section, original: List<String>, side2: Section, nextIndex: Int) = Block(
        side1 = side1.lines,
        original = original,
        side2 = side2.lines,
        nextIndex = nextIndex,
        label1 = cleanLabel(side1.header),
        label2 = cleanLabel(side2.header),
        role1 = roleOf(cleanLabel(side1.header)),
        role2 = roleOf(cleanLabel(side2.header))
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

    private companion object {
        val noTerminatingNewlineSuffix = " (no terminating newline)"
        val boilerplateHeader = Regex("""conflict \d+ of \d+( ends)?""", RegexOption.IGNORE_CASE)
        val destinationRole = Regex(""".*\((?:[a-z]+ destination|new parents)\)\s*$""", RegexOption.IGNORE_CASE)
        val movedRole = Regex(""".*\((?!parents of )[a-z]+ed revision\)\s*$""", RegexOption.IGNORE_CASE)
        val baseRole = Regex(""".*\(parents of [a-z]+ revision\)\s*$""", RegexOption.IGNORE_CASE)

        fun cleanLabel(raw: String?): String? {
            val trimmed = raw?.removeSuffix(noTerminatingNewlineSuffix)?.trim()
            if (trimmed.isNullOrBlank()) return null
            if (boilerplateHeader.matches(trimmed)) return null
            return trimmed
        }

        fun roleOf(label: String?): Role? = label?.let {
            when {
                destinationRole.matches(it) -> Role.DESTINATION
                movedRole.matches(it) -> Role.MOVED
                baseRole.matches(it) -> Role.BASE
                else -> null
            }
        }
    }
}
