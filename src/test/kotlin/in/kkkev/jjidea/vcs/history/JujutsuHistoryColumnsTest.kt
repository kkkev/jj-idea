package `in`.kkkev.jjidea.vcs.history

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.FileChange
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/**
 * Tests for [CommitTimestampColumnInfo], which renders/sorts the "Commit Time" column
 * in the platform file-history table.
 */
class JujutsuHistoryColumnsTest {
    private val repo = mockk<JujutsuRepository>()
    private val filePath = mockk<com.intellij.openapi.vcs.FilePath>()

    private fun revision(committerName: String, committerInstant: Instant?): JujutsuFileRevision {
        val entry = LogEntry(
            repo = repo,
            id = ChangeId("abc123", "ab"),
            commitId = CommitId("def456", "de"),
            underlyingDescription = "",
            committer = VcsUserImpl(committerName, "$committerName@example.com"),
            committerTimestamp = committerInstant
        )
        return JujutsuFileRevision(entry, filePath, FileChange.Status.MODIFIED, emptyList())
    }

    @Test
    fun `sorts by committer timestamp, not committer name`() {
        // Committer names sort in the opposite order to their timestamps, so a name-based
        // comparator and a date-based comparator disagree on the ordering.
        val earliest = revision("zack", Instant.parse("2025-01-01T00:00:00Z"))
        val middle = revision("mona", Instant.parse("2025-06-01T00:00:00Z"))
        val latest = revision("alice", Instant.parse("2025-12-01T00:00:00Z"))
        val noTimestamp = revision("aaron", null)

        val sorted = listOf(latest, noTimestamp, earliest, middle)
            .sortedWith(CommitTimestampColumnInfo().comparator)

        sorted shouldBe listOf(earliest, middle, latest, noTimestamp)
    }
}
