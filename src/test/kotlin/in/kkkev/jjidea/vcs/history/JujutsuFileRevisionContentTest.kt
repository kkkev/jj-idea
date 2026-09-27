package `in`.kkkev.jjidea.vcs.history

import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.VcsException
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.FileChange
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.mockRepo
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import org.junit.jupiter.api.Test

/**
 * [JujutsuFileRevision.loadContent] feeds file history's diff and "open revision" views; it must
 * return jj's exact bytes so binary files (e.g. images) aren't corrupted by a String round-trip.
 */
class JujutsuFileRevisionContentTest {
    private val repo = mockRepo()
    private val changeId = ChangeId("abc123", "ab", null)
    private val filePath = LocalFilePath("/repo/img/logo.png", false)
    private val revision = JujutsuFileRevision(
        LogEntry(repo = repo, id = changeId, commitId = CommitId("c0ffee"), underlyingDescription = ""),
        filePath,
        FileChange.Status.MODIFIED,
        emptyList()
    )

    @Test
    fun `loadContent returns jj's exact bytes`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        every { repo.commandExecutor.showBytes(filePath, changeId) } returns png

        revision.loadContent() shouldBe png
    }

    @Test
    fun `loadContent reports a jj failure as a VcsException naming the revision`() {
        every { repo.commandExecutor.showBytes(filePath, changeId) } throws VcsException("No such path")

        val e = shouldThrow<VcsException> { revision.loadContent() }

        e.message shouldContain "No such path"
        e.message shouldContain changeId.toString()
    }
}
