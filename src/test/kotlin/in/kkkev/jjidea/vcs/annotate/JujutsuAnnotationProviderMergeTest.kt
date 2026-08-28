package `in`.kkkev.jjidea.vcs.annotate

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.testFramework.junit5.TestApplication
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.FileAtVersion
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.MergeParentOf
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.changes.ChangeIdRevisionNumber
import `in`.kkkev.jjidea.vcs.changes.MergeParentRevisionNumber
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryFor
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression tests for a follow-up crash: annotating a merge working copy no longer throws
 * (an arbitrary-first-parent fallback avoided that), but the resulting annotation's line count
 * diverged wildly from the resolved file, tripping the platform's "Number of lines annotated ...
 * is not equal to number of lines in the file" warning. [JujutsuAnnotationProvider.annotateMerge]
 * reconciles blame from every real parent instead — see [MergeAnnotationReconciler].
 *
 * Platform test (not a plain unit test): the code under test resolves a [FilePath] via
 * `VirtualFile.filePath` (com.intellij.vcsUtil.VcsUtil), which requires an initialized
 * IntelliJ Application.
 */
@Tag("platform")
@TestApplication
class JujutsuAnnotationProviderMergeTest {
    private val project = mockk<Project>()
    private val vcs = JujutsuVcs(project)
    private val repo = mockk<JujutsuRepository> {
        // Stubbed for every test: getAnnotationLines records per-repo annotate timing
        // (jj-idea-1sza) keyed by repo.directory.path on every real annotate call.
        every { directory } returns MockVirtualFile("repo")
    }
    private val commandExecutor = mockk<CommandExecutor>()
    private val file = MockVirtualFile("test.txt")
    private val provider = JujutsuAnnotationProvider(project, vcs)

    private val childRevision: Revision = ChangeId("merge1", "merge1")
    private val parent1: ChangeId = ChangeId("parent1", "parent1")
    private val parent2: ChangeId = ChangeId("parent2", "parent2")

    private fun mergeCommit(parentIds: List<ChangeId>) = LogEntry(
        repo = repo,
        id = ChangeId("merge1", "merge1"),
        commitId = CommitId("merge1", "merge1"),
        underlyingDescription = "merge",
        parentIds = parentIds
    )

    private fun annotateResult(changeId: ChangeId, content: String) = CommandExecutor.CommandResult(
        0,
        // Mirrors AnnotationParser.TEMPLATE: fields joined by "\0", record ends with `content`'s
        // own trailing "\n" (no extra separator after it) — see jj-idea-3191.
        "${changeId.full}\u0000${changeId.short}\u0000\u0000c-${changeId.full}\u0000c-${changeId.short}\u0000" +
            "Author\u0000author@example.com\u00001700000000\u0000\"desc\"\u0000\u0000$content",
        ""
    )

    @Test
    fun `annotateMerge reconciles blame from every real parent`() {
        every { repo.commandExecutor } returns commandExecutor
        every { repo.getLogEntry(childRevision) } returns mergeCommit(listOf(parent1, parent2))
        every { repo.workingCopy } returns mergeCommit(listOf(parent1, parent2))
        every { commandExecutor.show(any(), childRevision) } returns
            CommandExecutor.CommandResult(0, "line one\nline two\n", "")
        every { commandExecutor.diffGitFile(childRevision, any()) } returns
            CommandExecutor.CommandResult(0, "", "")
        every { commandExecutor.annotate(file, parent1, any()) } returns
            annotateResult(parent1, "line one\n")
        every { commandExecutor.annotate(file, parent2, any()) } returns
            annotateResult(parent2, "line two\n")

        val result = provider.annotateMerge(file, MergeParentOf(childRevision), repo)

        result.shouldNotBeNull()
        result.getLineCount() shouldBe 2
        result.getLineRevisionNumber(0) shouldBe ChangeIdRevisionNumber(parent1)
        result.getLineRevisionNumber(1) shouldBe ChangeIdRevisionNumber(parent2)
    }

    // Operation-count regression test: the reconciler must issue exactly one `jj file annotate`
    // per real parent, independent of file size or repository history — not proportional to
    // anything else in the repo.
    @Test
    fun `annotateMerge issues exactly one annotate call per real parent`() {
        every { repo.commandExecutor } returns commandExecutor
        every { repo.getLogEntry(childRevision) } returns mergeCommit(listOf(parent1, parent2))
        every { repo.workingCopy } returns mergeCommit(listOf(parent1, parent2))
        every { commandExecutor.show(any(), childRevision) } returns
            CommandExecutor.CommandResult(0, "line one\nline two\n", "")
        every { commandExecutor.diffGitFile(childRevision, any()) } returns
            CommandExecutor.CommandResult(0, "", "")
        every { commandExecutor.annotate(file, parent1, any()) } returns
            annotateResult(parent1, "line one\n")
        every { commandExecutor.annotate(file, parent2, any()) } returns
            annotateResult(parent2, "line two\n")

        provider.annotateMerge(file, MergeParentOf(childRevision), repo)

        verify(exactly = 1) { commandExecutor.annotate(file, parent1, any()) }
        verify(exactly = 1) { commandExecutor.annotate(file, parent2, any()) }
    }

    @Test
    fun `annotateMerge falls back to null only when every parent's annotate call fails`() {
        every { repo.commandExecutor } returns commandExecutor
        every { repo.getLogEntry(childRevision) } returns mergeCommit(listOf(parent1, parent2))
        every { commandExecutor.show(any(), childRevision) } returns
            CommandExecutor.CommandResult(0, "line one\n", "")
        every { commandExecutor.diffGitFile(childRevision, any()) } returns
            CommandExecutor.CommandResult(0, "", "")
        every { commandExecutor.annotate(file, parent1, any()) } returns
            CommandExecutor.CommandResult(1, "", "boom")
        every { commandExecutor.annotate(file, parent2, any()) } returns
            CommandExecutor.CommandResult(1, "", "boom")

        val result = provider.annotateMerge(file, MergeParentOf(childRevision), repo)

        result.shouldBeNull()
    }

    // Regression test for jj-idea-hpvu: a parent whose annotate call timed out (rather than
    // failing outright) must be treated the same as an ordinary failure by the reconciliation
    // fallback, not crash or hang.
    @Test
    fun `annotateMerge falls back to null when every parent's annotate call times out`() {
        every { repo.commandExecutor } returns commandExecutor
        every { repo.getLogEntry(childRevision) } returns mergeCommit(listOf(parent1, parent2))
        every { commandExecutor.show(any(), childRevision) } returns
            CommandExecutor.CommandResult(0, "line one\n", "")
        every { commandExecutor.diffGitFile(childRevision, any()) } returns
            CommandExecutor.CommandResult(0, "", "")
        every { commandExecutor.annotate(file, parent1, any()) } returns
            CommandExecutor.CommandResult(exitCode = -1, stdout = "", stderr = "", timedOut = true)
        every { commandExecutor.annotate(file, parent2, any()) } returns
            CommandExecutor.CommandResult(exitCode = -1, stdout = "", stderr = "", timedOut = true)

        val result = provider.annotateMerge(file, MergeParentOf(childRevision), repo)

        result.shouldBeNull()
    }

    // Regression test: a criss-cross merge where the file was only added on one side (or
    // otherwise doesn't exist at one parent) previously aborted the whole reconciliation with
    // "No such path", crashing both the merge path and its first-parent fallback. A parent
    // lacking the file must be skipped, not treated as a hard failure, as long as at least one
    // other parent can supply blame.
    @Test
    fun `annotateMerge tolerates a parent missing the file entirely`() {
        every { repo.commandExecutor } returns commandExecutor
        every { repo.getLogEntry(childRevision) } returns mergeCommit(listOf(parent1, parent2))
        every { repo.workingCopy } returns mergeCommit(listOf(parent1, parent2))
        every { commandExecutor.show(any(), childRevision) } returns
            CommandExecutor.CommandResult(0, "line one\n", "")
        every { commandExecutor.diffGitFile(childRevision, any()) } returns
            CommandExecutor.CommandResult(0, "", "")
        every { commandExecutor.annotate(file, parent1, any()) } returns
            CommandExecutor.CommandResult(1, "", "Error: No such path: test.txt")
        every { commandExecutor.annotate(file, parent2, any()) } returns
            annotateResult(parent2, "line one\n")

        val result = provider.annotateMerge(file, MergeParentOf(childRevision), repo)

        result.shouldNotBeNull()
        result.getLineCount() shouldBe 1
        result.getLineRevisionNumber(0) shouldBe ChangeIdRevisionNumber(parent2)
    }

    // jj-idea-hq4d: the platform's Annotate action on a merge commit's auto-merged-parent file
    // reaches annotate(FilePath, VcsRevisionNumber) with a MergeParentRevisionNumber. That must
    // route through the same multi-parent reconciliation as annotateMerge, not a plain
    // single-revision annotate (which would crash: a MergeParentOf locator isn't a real revision).
    @Test
    fun `annotate(path, revision) routes a MergeParentRevisionNumber through annotateMerge`() {
        mockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        try {
            val filePath = LocalFilePath("/repo/test.txt", false)
            every { project.jujutsuRepositoryFor(filePath) } returns repo
            every { repo.getVirtualFile(FileAtVersion(filePath, MergeParentOf(childRevision))) } returns file
            every { repo.commandExecutor } returns commandExecutor
            every { repo.getLogEntry(childRevision) } returns mergeCommit(listOf(parent1, parent2))
            every { repo.workingCopy } returns mergeCommit(listOf(parent1, parent2))
            every { commandExecutor.show(any(), childRevision) } returns
                CommandExecutor.CommandResult(0, "line one\nline two\n", "")
            every { commandExecutor.diffGitFile(childRevision, any()) } returns
                CommandExecutor.CommandResult(0, "", "")
            every { commandExecutor.annotate(file, parent1, any()) } returns
                annotateResult(parent1, "line one\n")
            every { commandExecutor.annotate(file, parent2, any()) } returns
                annotateResult(parent2, "line two\n")

            val result = provider.annotate(filePath, MergeParentRevisionNumber(childRevision))

            result.getLineCount() shouldBe 2
            result.getLineRevisionNumber(0) shouldBe ChangeIdRevisionNumber(parent1)
            result.getLineRevisionNumber(1) shouldBe ChangeIdRevisionNumber(parent2)
        } finally {
            unmockkStatic("in.kkkev.jjidea.vcs.VcsExtensionsKt")
        }
    }
}
