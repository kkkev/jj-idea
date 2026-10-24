package `in`.kkkev.jjidea.vcs.history

import com.intellij.openapi.vcs.LocalFilePath
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.FileChange
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.MergeParentOf
import `in`.kkkev.jjidea.jj.fileAt
import `in`.kkkev.jjidea.jj.mockRepo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test

/** jj-idea-d0bp: Show Diff on a file-history row diffs against the commit's real parent, not the row below it. */
class JujutsuDiffFromHistoryHandlerTest {
    private val repo = mockRepo()
    private val filePath = LocalFilePath("/repo/test-file-1.txt", false)
    private val handler = JujutsuDiffFromHistoryHandler()

    private val parent = ChangeId("kzxrvruo", "k", null)
    private val parentCommit = CommitId("aaaa")
    private val commit = CommitId("caed470d")

    private fun revision(
        status: FileChange.Status,
        parentIds: List<ChangeId> = listOf(parent),
        parentCommitIds: List<CommitId> = listOf(parentCommit)
    ) = JujutsuFileRevision(
        LogEntry(
            repo = repo,
            id = ChangeId("caed", "c", null),
            commitId = commit,
            underlyingDescription = "",
            parentIds = parentIds,
            parentCommitIds = parentCommitIds
        ),
        filePath,
        status,
        emptyList()
    )

    @Test
    fun `sides come from the commit's own file change, not the row below`() {
        val rev = revision(FileChange.Status.MODIFIED)
        val change = FileChange.Modified(filePath.fileAt(parentCommit), filePath.fileAt(commit))
        every { repo.logService.getFileChanges(rev.entry, filePath) } returns Result.success(listOf(change))

        handler.diffSidesFor(rev) shouldBe (change.before to change.after)

        verify(exactly = 1) { repo.logService.getFileChanges(any(), any()) }
    }

    @Test
    fun `fallback diffs a single-parent commit against its parent commit`() {
        val rev = revision(FileChange.Status.MODIFIED)
        every { repo.logService.getFileChanges(any(), any()) } returns Result.failure(RuntimeException("boom"))

        handler.diffSidesFor(rev) shouldBe (filePath.fileAt(parentCommit) to filePath.fileAt(commit))
    }

    @Test
    fun `fallback diffs a merge commit against the merge parent`() {
        val rev = revision(
            FileChange.Status.MODIFIED,
            parentIds = listOf(parent, ChangeId("syyuswxr", "s", null)),
            parentCommitIds = listOf(parentCommit, CommitId("bbbb"))
        )
        every { repo.logService.getFileChanges(any(), any()) } returns Result.success(emptyList())

        val (before, after) = handler.diffSidesFor(rev)

        before!!.contentLocator.shouldBeInstanceOf<MergeParentOf>()
        after shouldBe filePath.fileAt(commit)
    }

    @Test
    fun `fallback has no before side for an added file or a root commit`() {
        every { repo.logService.getFileChanges(any(), any()) } returns Result.success(emptyList())

        handler.diffSidesFor(revision(FileChange.Status.ADDED)).first shouldBe null
        handler.diffSidesFor(revision(FileChange.Status.MODIFIED, emptyList(), emptyList())).first shouldBe null
    }

    @Test
    fun `fallback has no after side for a deleted file`() {
        every { repo.logService.getFileChanges(any(), any()) } returns Result.success(emptyList())

        handler.diffSidesFor(revision(FileChange.Status.DELETED)).second shouldBe null
    }

    @Test
    fun `history provider supplies the handler`() {
        JujutsuHistoryProvider(io.mockk.mockk(relaxed = true)).historyDiffHandler
            .shouldBeInstanceOf<JujutsuDiffFromHistoryHandler>()
    }
}
