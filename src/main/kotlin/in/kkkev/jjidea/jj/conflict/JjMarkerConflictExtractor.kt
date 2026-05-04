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
            if (line.startsWith("<<<<<<< Conflict ")) {
                foundAnyConflict = true
                i++
                val side1 = mutableListOf<String>()
                val base = mutableListOf<String>()
                val side2 = mutableListOf<String>()
                var section = Section.NONE
                var closed = false

                while (i < lines.size) {
                    val inner = lines[i]
                    when {
                        inner.startsWith(">>>>>>> Conflict ") -> {
                            closed = true
                            i++
                            break
                        }
                        // Header lines set section and are intentionally not added to content
                        inner.startsWith("+++++++ Contents of side #1") -> section = Section.SIDE1
                        inner.startsWith("------- Base") -> section = Section.BASE
                        inner.startsWith("+++++++ Contents of side #2") -> section = Section.SIDE2
                        else -> when (section) {
                            Section.SIDE1 -> side1.add(inner)
                            Section.BASE -> base.add(inner)
                            Section.SIDE2 -> side2.add(inner)
                            Section.NONE -> {}
                        }
                    }
                    i++
                }

                if (!closed) return null
                current.addAll(side1)
                original.addAll(base)
                last.addAll(side2)
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

    private enum class Section { NONE, SIDE1, BASE, SIDE2 }
}
