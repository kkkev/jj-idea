package `in`.kkkev.jjidea.vcs.annotate

import `in`.kkkev.jjidea.jj.AnnotationLine
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class MergeAnnotationReconcilerTest {
    private val repo = mockk<JujutsuRepository>()

    private fun mergeCommit(id: String, parentIds: List<ChangeId>) = LogEntry(
        repo = repo,
        id = ChangeId(id, id),
        commitId = CommitId(id, id),
        underlyingDescription = "merge",
        parentIdentifiers = parentIds.map { LogEntry.Identifiers(it, CommitId(it.full, it.full)) },
        author = VcsUserImpl("Merger", "merger@example.com")
    )

    private fun annotationLine(id: String, content: String, lineNumber: Int) = AnnotationLine(
        id = ChangeId(id, id),
        commitId = CommitId(id, id),
        author = VcsUserImpl("Author-$id", "$id@example.com"),
        authorTimestamp = null,
        description = Description("change $id"),
        lineContent = content,
        lineNumber = lineNumber
    )

    @Test
    fun `every line attributed to parent1 when merge content matches parent1`() {
        val parent1 = ChangeId("parent1", "parent1")
        val parent2 = ChangeId("parent2", "parent2")
        val parent1Lines = listOf(
            annotationLine("parent1", "line one\n", 1),
            annotationLine("parent1", "line two\n", 2)
        )
        val mergeContent = "line one\nline two\n"

        val result = MergeAnnotationReconciler.reconcile(
            mergeContent,
            mergeCommit("merge1", listOf(parent1, parent2)),
            listOf(parent1Lines, emptyList())
        )

        result.map { it.id.full } shouldContainExactly listOf("parent1", "parent1")
        result.map { it.lineContent } shouldContainExactly listOf("line one\n", "line two\n")
        result.map { it.lineNumber } shouldContainExactly listOf(1, 2)
    }

    @Test
    fun `every line attributed to parent2 when merge content matches parent2`() {
        val parent1 = ChangeId("parent1", "parent1")
        val parent2 = ChangeId("parent2", "parent2")
        val parent2Lines = listOf(
            annotationLine("parent2", "alpha\n", 1),
            annotationLine("parent2", "beta\n", 2)
        )
        val mergeContent = "alpha\nbeta\n"

        val result = MergeAnnotationReconciler.reconcile(
            mergeContent,
            mergeCommit("merge1", listOf(parent1, parent2)),
            listOf(emptyList(), parent2Lines)
        )

        result.map { it.id.full } shouldContainExactly listOf("parent2", "parent2")
    }

    @Test
    fun `line present in neither parent is attributed to the merge commit with both parent ids`() {
        val parent1 = ChangeId("parent1", "parent1")
        val parent2 = ChangeId("parent2", "parent2")
        val parent1Lines = listOf(annotationLine("parent1", "unchanged\n", 1))
        val parent2Lines = listOf(annotationLine("parent2", "unchanged\n", 1))
        val mergeContent = "unchanged\nresolved conflict line\n"

        val result = MergeAnnotationReconciler.reconcile(
            mergeContent,
            mergeCommit("merge1", listOf(parent1, parent2)),
            listOf(parent1Lines, parent2Lines)
        )

        result shouldHaveLineCount 2
        result[0].id.full shouldBe "parent1"
        result[1].id.full shouldBe "merge1"
        result[1].parentIds.map { it.full } shouldContainExactly listOf("parent1", "parent2")
        result[1].lineContent shouldBe "resolved conflict line\n"
    }

    @Test
    fun `mixed file attributes each line to whichever parent supplied it`() {
        val parent1 = ChangeId("parent1", "parent1")
        val parent2 = ChangeId("parent2", "parent2")
        val parent1Lines = listOf(
            annotationLine("parent1", "from parent1 a\n", 1),
            annotationLine("parent1", "from parent1 b\n", 2)
        )
        val parent2Lines = listOf(
            annotationLine("parent2", "from parent2 a\n", 1),
            annotationLine("parent2", "from parent2 b\n", 2)
        )
        // Merge combines a line from each parent plus one resolved (conflict) line.
        val mergeContent = "from parent1 a\nfrom parent2 b\nresolved\n"

        val result = MergeAnnotationReconciler.reconcile(
            mergeContent,
            mergeCommit("merge1", listOf(parent1, parent2)),
            listOf(parent1Lines, parent2Lines)
        )

        result shouldHaveLineCount 3
        result.map { it.id.full } shouldContainExactly listOf("parent1", "parent2", "merge1")
        result.map { it.lineContent } shouldContainExactly listOf(
            "from parent1 a\n",
            "from parent2 b\n",
            "resolved\n"
        )
    }

    private infix fun List<AnnotationLine>.shouldHaveLineCount(expected: Int) {
        this.size shouldBe expected
    }
}
