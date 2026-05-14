package `in`.kkkev.jjidea.jj.conflict

import com.intellij.openapi.vcs.merge.MergeData

class JjMarkerConflictExtractor : ConflictExtractor {
    override fun extract(fileContent: ByteArray): MergeData? {
        val lines = fileContent.toString(Charsets.UTF_8).split('\n')
        val current = mutableListOf<String>()
        val original = mutableListOf<String>()
        val last = mutableListOf<String>()
        var foundAnyConflict = false
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("<<<<<<<")) {
                foundAnyConflict = true
                i++
                val block = parseBlock(lines, i) ?: return null
                i = block.nextIndex
                current.addAll(block.current)
                original.addAll(block.original)
                last.addAll(block.last)
            } else {
                current.add(line)
                original.add(line)
                last.add(line)
                i++
            }
        }

        if (!foundAnyConflict) return null

        return MergeData().also {
            it.CURRENT = current.joinToString("\n").toByteArray(Charsets.UTF_8)
            it.ORIGINAL = original.joinToString("\n").toByteArray(Charsets.UTF_8)
            it.LAST = last.joinToString("\n").toByteArray(Charsets.UTF_8)
        }
    }

    private data class Block(
        val current: List<String>,
        val original: List<String>,
        val last: List<String>,
        val nextIndex: Int
    )

    private enum class Kind { SIDE1, BASE, SIDE2, DIFF, CONTENT }

    private fun parseBlock(lines: List<String>, startIndex: Int): Block? {
        var i = startIndex
        val sections = mutableListOf<Pair<Kind, List<String>>>()
        var kind: Kind? = null
        val buf = mutableListOf<String>()
        // Collects lines before the first format-specific marker (SIDE1 in git-style format)
        val preHeaderBuf = mutableListOf<String>()
        var closed = false

        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith(">>>>>>>") -> {
                    kind?.let { sections.add(it to buf.toList()) }
                        ?: run { if (preHeaderBuf.isNotEmpty()) sections.add(Kind.SIDE1 to preHeaderBuf.toList()) }
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
                line.startsWith("|||||||") -> {
                    kind?.let { sections.add(it to buf.toList()) } ?: sections.add(Kind.SIDE1 to preHeaderBuf.toList())
                    buf.clear()
                    kind = Kind.BASE
                }
                line == "=======" && (kind == null || kind == Kind.BASE) -> {
                    if (kind == null) sections.add(Kind.SIDE1 to preHeaderBuf.toList())
                    else sections.add(Kind.BASE to buf.toList())
                    buf.clear()
                    kind = Kind.SIDE2
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
                    kind?.let { sections.add(it to buf.toList()) }
                    buf.clear()
                    kind = when {
                        line.contains("Contents of side #1") -> Kind.SIDE1
                        line.contains("Contents of side #2") -> Kind.SIDE2
                        else -> Kind.CONTENT
                    }
                }
                line.startsWith("-------") -> {
                    kind?.let { sections.add(it to buf.toList()) }
                    buf.clear()
                    kind = Kind.BASE
                }
                line.startsWith("%%%%%%%") -> {
                    kind?.let { sections.add(it to buf.toList()) }
                    buf.clear()
                    kind = Kind.DIFF
                }
                // Skip the "\\\\\\\ to: ..." diff-section header continuation line
                line.startsWith("\\\\\\") -> Unit
                else -> kind?.let { buf.add(line) } ?: preHeaderBuf.add(line)
            }
            i++
        }

        if (!closed) return null

        // Git format (with base): side1 + base + side2
        val side1 = sections.find { it.first == Kind.SIDE1 }?.second
        val base = sections.find { it.first == Kind.BASE }?.second
        val side2 = sections.find { it.first == Kind.SIDE2 }?.second
        if (side1 != null && base != null && side2 != null) {
            return Block(side1, base, side2, i)
        }
        // Git format (without base): side1 + side2
        if (side1 != null && side2 != null) {
            return Block(side1, emptyList(), side2, i)
        }

        // Diff format: CONTENT section (full side1) + DIFF section (unified diff from base to side2)
        //   +++++++  side1 content  →  Kind.CONTENT
        //   %%%%%%%  diff …         →  Kind.DIFF
        //   \\\\\\\  to: …          →  (skipped)
        //   - base line
        //   + side2 line
        val diffLines = sections.find { it.first == Kind.DIFF }?.second
        val contentSections = sections.filter { it.first == Kind.CONTENT }
        if (diffLines != null) {
            // Reconstruct base (orig) and side2 (theirs) from the unified diff.
            // Context lines (no prefix) appear in both; - lines are base-only; + lines are side2-only.
            val side2 = mutableListOf<String>()
            val orig = mutableListOf<String>()
            for (dl in diffLines) {
                when {
                    dl.startsWith("+") -> side2.add(dl.substring(1))
                    dl.startsWith("-") -> orig.add(dl.substring(1))
                    else -> { side2.add(dl); orig.add(dl) }
                }
            }
            // side1 (ours) is the full content from the +++++++  CONTENT section
            val side1 = contentSections.lastOrNull()?.second ?: emptyList()
            return Block(side1, orig, side2, i)
        }

        // Fallback: treat collected CONTENT sections as CURRENT and LAST
        val contents = sections.map { it.second }
        return Block(
            contents.firstOrNull() ?: emptyList(),
            emptyList(),
            contents.lastOrNull() ?: emptyList(),
            i
        )
    }
}
