package `in`.kkkev.jjidea.jj.conflict

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DiffSectionLinesTest {
    @Test
    fun `classifies lines by prefix and covers only the given span`() {
        val text = "before\n-base\n+dest\n keep\nafter\n"
        val start = text.indexOf("-base")
        val end = text.indexOf("after")

        val lines = diffSectionLines(text, start, end)

        lines.map { it.kind } shouldBe listOf(DiffLineKind.REMOVED, DiffLineKind.ADDED, DiffLineKind.CONTEXT)
        lines.map { text.substring(it.prefixOffset, it.lineEnd) } shouldBe listOf("-base", "+dest", " keep")
    }

    @Test
    fun `empty span yields nothing and empty line is context`() {
        diffSectionLines("abc", 1, 1) shouldBe emptyList()
        diffSectionLines("a\n\nb\n", 2, 3).single().kind shouldBe DiffLineKind.CONTEXT
    }

    @Test
    fun `parser marks the diff side of a DIFF block`() {
        val block = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.diffDestinationFirst).single()
        block.side1.isDiffSection shouldBe true
        block.side2.isDiffSection shouldBe false
    }

    @Test
    fun `marker runs group consecutive marker lines and name the following side`() {
        val git = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.gitWithBase).single()
        git.markerRuns.map { it.next } shouldBe listOf(AcceptChoice.SIDE1, AcceptChoice.BASE, AcceptChoice.SIDE2, null)

        val diff = JjConflictBlockParser.parseAll(ConflictMarkerFixtures.diffDestinationFirst).single()
        // "<<<<<<<"+"%%%%%%%"+"\\\" collapse into one run, then "+++++++", then the closer.
        diff.markerRuns.map { it.endLine - it.startLine } shouldBe listOf(2, 0, 0)
        diff.markerRuns.map { it.next } shouldBe listOf(AcceptChoice.SIDE1, AcceptChoice.SIDE2, null)
    }
}
