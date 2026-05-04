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
        var closed = false

        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith(">>>>>>>") -> {
                    kind?.let { sections.add(it to buf.toList()) }
                    closed = true
                    i++
                    break
                }
                line.startsWith("+++++++") -> {
                    kind?.let { sections.add(it to buf.toList()) }
                    buf.clear()
                    // Header lines set section and are intentionally not added to content
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
                else -> kind?.let { buf.add(line) }
            }
            i++
        }

        if (!closed) return null

        // Old format: explicit SIDE1 + BASE + SIDE2
        val side1 = sections.find { it.first == Kind.SIDE1 }?.second
        val base = sections.find { it.first == Kind.BASE }?.second
        val side2 = sections.find { it.first == Kind.SIDE2 }?.second
        if (side1 != null && base != null && side2 != null) {
            return Block(side1, base, side2, i)
        }

        // New format: DIFF section (reconstruct sides from unified diff) + CONTENT section(s)
        val diffLines = sections.find { it.first == Kind.DIFF }?.second
        val contentSections = sections.filter { it.first == Kind.CONTENT }
        if (diffLines != null) {
            val cur = mutableListOf<String>()
            val orig = mutableListOf<String>()
            for (dl in diffLines) {
                when {
                    dl.startsWith("+") -> cur.add(dl.substring(1))
                    dl.startsWith("-") -> orig.add(dl.substring(1))
                    else -> {
                        cur.add(dl)
                        orig.add(dl)
                    }
                }
            }
            return Block(cur, orig, contentSections.lastOrNull()?.second ?: emptyList(), i)
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
