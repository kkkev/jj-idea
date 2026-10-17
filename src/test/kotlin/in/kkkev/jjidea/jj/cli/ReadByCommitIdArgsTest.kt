package `in`.kkkev.jjidea.jj.cli

import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.MergeParentOf
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** jj-idea-wiz0: reads are addressed by the full commit id (never the short form); writes keep the change id. */
class ReadByCommitIdArgsTest {
    private val full = "0123456789abcdef0123456789abcdef01234567"
    private val commit = CommitId(full, "01")
    private val root = mockk<VirtualFile> { every { path } returns "/repo" }
    private val file = LocalFilePath("/repo/f.txt", false)

    @Test
    fun `read arg builders send the full commit id`() {
        diffSummaryArgs(commit, null, root).args shouldBe listOf("diff", "--summary", "-r", full)
        diffGitArgs(commit).args shouldBe listOf("diff", "--git", "-r", full)
        diffGitFileArgs(commit, file, root).args.slice(0..3) shouldBe listOf("diff", "--git", "-r", full)
        fileListArgs(listOf("f.txt"), commit).args.slice(0..3) shouldBe listOf("file", "list", "-r", full)
    }

    private fun entry(isWorkingCopy: Boolean, parents: Int) = LogEntry(
        repo = mockk<JujutsuRepository>(),
        id = ChangeId("kkkk", "k", null),
        commitId = commit,
        underlyingDescription = "",
        parentIds = List(parents) { ChangeId("p$it", "p", null) },
        parentCommitIds = List(parents) { CommitId("a$it".padEnd(40, '0')) },
        isWorkingCopy = isWorkingCopy
    )

    @Test
    fun `a committed entry reads as its commit id and its parent by commit id`() {
        val e = entry(isWorkingCopy = false, parents = 1)

        e.contentLocator shouldBe commit
        e.readRevision shouldBe commit
        e.parentContentLocator shouldBe e.parentCommitIds.single()
    }

    @Test
    fun `a merge reads its parent tree through its commit id`() {
        entry(isWorkingCopy = false, parents = 2).parentContentLocator shouldBe MergeParentOf(commit)
    }

    @Test
    fun `the working copy keeps its change id because its content is live`() {
        val e = entry(isWorkingCopy = true, parents = 1)

        e.contentLocator shouldBe e.id
        e.readRevision shouldBe e.id
    }
}
