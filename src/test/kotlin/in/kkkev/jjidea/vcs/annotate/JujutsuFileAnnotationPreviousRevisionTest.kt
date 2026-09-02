package `in`.kkkev.jjidea.vcs.annotate

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsKey
import com.intellij.testFramework.junit5.TestApplication
import `in`.kkkev.jjidea.jj.AnnotationLine
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.commandResult
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression tests for jj-idea-o95r: "Annotate Previous Revision" on a line whose owning change
 * *added* the file (rather than modifying an existing line) must decline gracefully instead of
 * throwing a raw "No such path" [com.intellij.openapi.vcs.VcsException] — that line's single
 * parent legitimately lacks the file, mirroring [JujutsuFileAnnotationTest]'s existing coverage
 * of the multi-parent (merge-commit) decline.
 *
 * Platform test (not a plain unit test), split out from [JujutsuFileAnnotationTest]: unlike that
 * file's purely in-memory previous-revision cases (root line, merge line), a single-parent line
 * now probes `repo.commandExecutor.show()` via `JujutsuFileAnnotation.fileExistsAt`, which
 * resolves a `FilePath` via `VirtualFile.filePath` (com.intellij.vcsUtil.VcsUtil) — requiring an
 * initialized IntelliJ Application, same as [JujutsuAnnotationProviderMergeTest].
 */
@Tag("platform")
@TestApplication
class JujutsuFileAnnotationPreviousRevisionTest {
    private val commandExecutor = mockk<CommandExecutor>()
    private val repo = mockk<JujutsuRepository>()
    private val parent = ChangeId("parent1", "parent1")

    private fun line(id: String, parentIds: List<ChangeId>) = AnnotationLine(
        id = ChangeId(id, id),
        commitId = CommitId(id, id),
        author = VcsUserImpl("Author", "author@example.com"),
        authorTimestamp = Instant.fromEpochSeconds(0),
        description = Description("desc"),
        parentIds = parentIds,
        lineContent = "line",
        lineNumber = 1
    )

    private fun annotationWith(vararg lines: AnnotationLine) = JujutsuFileAnnotation(
        project = mockk<Project>(),
        repo = repo,
        file = MockVirtualFile(false, "file.txt"),
        annotationLines = lines.toList(),
        vcsKey = mockk<VcsKey>()
    )

    @Test
    fun `previous revision for a single-parent line is that parent when the parent has the file`() {
        every { repo.commandExecutor } returns commandExecutor
        every { commandExecutor.show(any(), parent) } returns commandResult(0, "content", "")
        val annotation = annotationWith(line("child1", parentIds = listOf(parent)))

        val previous = annotation.getPreviousFileRevisionProvider()?.getPreviousRevision(0)

        previous?.revisionNumber shouldBe ChangeIdRevisionNumber(parent)
    }

    @Test
    fun `previous revision declines when the line's own change added the file (parent lacks it)`() {
        every { repo.commandExecutor } returns commandExecutor
        every { commandExecutor.show(any(), parent) } returns
            commandResult(1, "", "Error: No such path: file.txt")
        val annotation = annotationWith(line("child1", parentIds = listOf(parent)))

        val previous = annotation.getPreviousFileRevisionProvider()?.getPreviousRevision(0)

        previous.shouldBeNull()
    }
}
