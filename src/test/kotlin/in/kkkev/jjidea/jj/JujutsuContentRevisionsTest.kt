package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Two [JujutsuRepository.createContentRevision] calls for the same path + locator must produce
 * `ContentRevision`s that are `equal` (and share a `hashCode`) so that the platform's diff request
 * cache (keyed via `ChangeDiffRequestProducer.equals`) treats a repeated background refresh as a
 * cache hit rather than rebuilding the diff viewer and resetting its scroll position (jj-idea-q6vn).
 * See [ChangeDiffRequestProducer.isEquals][com.intellij.openapi.vcs.changes.actions.diff.ChangeDiffRequestProducer]
 * (platform), which special-cases only [CurrentContentRevision] and otherwise falls back to
 * `ContentRevision.equals`.
 */
class JujutsuContentRevisionsTest {
    private fun path(relativePath: String) = LocalFilePath(relativePath, false)

    private val changeIdA = ChangeId("aaa", "aaa", null)
    private val changeIdB = ChangeId("bbb", "bbb", null)

    @Test
    fun `ContentLogEntryImpl instances are equal for the same repo, path and change id`() {
        val repo = mockRepo()
        val filePath = path("src/Main.kt")

        val first = ContentLogEntryImpl(repo, filePath, changeIdA)
        val second = ContentLogEntryImpl(repo, filePath, changeIdA)

        first shouldBe second
        first.hashCode() shouldBe second.hashCode()
    }

    @Test
    fun `ContentLogEntryImpl instances differ when the change id differs`() {
        val repo = mockRepo()
        val filePath = path("src/Main.kt")

        ContentLogEntryImpl(repo, filePath, changeIdA) shouldNotBe ContentLogEntryImpl(repo, filePath, changeIdB)
    }

    @Test
    fun `ContentLogEntryImpl instances differ when the path differs`() {
        val repo = mockRepo()

        ContentLogEntryImpl(repo, path("src/A.kt"), changeIdA) shouldNotBe
            ContentLogEntryImpl(repo, path("src/B.kt"), changeIdA)
    }

    @Test
    fun `MergeParentContentRevision instances are equal for the same repo, path and merge parent`() {
        val repo = mockRepo()
        val filePath = path("src/Main.kt")
        val mergeParentOf = MergeParentOf(changeIdA)

        val first = MergeParentContentRevision(repo, filePath, mergeParentOf)
        val second = MergeParentContentRevision(repo, filePath, mergeParentOf)

        first shouldBe second
        first.hashCode() shouldBe second.hashCode()
    }

    @Test
    fun `EmptyContentRevisionImpl instances are equal for the same path`() {
        val filePath = path("src/Main.kt")

        EmptyContentRevisionImpl(filePath) shouldBe EmptyContentRevisionImpl(filePath)
        EmptyContentRevisionImpl(filePath) shouldNotBe EmptyContentRevisionImpl(path("src/Other.kt"))
    }

    @Test
    fun `ContentLogEntryImpl is never confused with CurrentContentRevision`() {
        val filePath = path("src/Main.kt")

        val logEntryRevision = ContentLogEntryImpl(mockRepo(), filePath, changeIdA)

        logEntryRevision.shouldBeInstanceOf<ContentLogEntryImpl>()
        (logEntryRevision == CurrentContentRevision(filePath)) shouldBe false
    }

    // Binary content (GitHub: image diffs rendered as text). A PNG signature can't survive a UTF-8
    // String round-trip: 0x89 isn't valid UTF-8 and decodes to U+FFFD, re-encoding as 3 bytes.

    @Test
    fun `PNG signature is corrupted by a String round-trip, which is why content must stay bytes`() {
        String(PNG_SIGNATURE, Charsets.UTF_8).toByteArray() shouldNotBe PNG_SIGNATURE
    }

    @Test
    fun `ContentLogEntryImpl is byte-backed and returns jj's exact bytes`() {
        val repo = mockRepo()
        val filePath = path("img/logo.png")
        every { repo.commandExecutor.showBytes(filePath, changeIdA) } returns PNG_SIGNATURE

        val revision = ContentLogEntryImpl(repo, filePath, changeIdA)

        revision.shouldBeInstanceOf<ByteBackedContentRevision>()
        revision.contentAsBytes shouldBe PNG_SIGNATURE
    }

    @Test
    fun `contentBytes prefers a byte-backed revision's bytes over its decoded text`() {
        val revision = mockk<ByteBackedContentRevision> {
            every { contentAsBytes } returns PNG_SIGNATURE
            every { content } returns String(PNG_SIGNATURE, Charsets.UTF_8)
        }

        revision.contentBytes() shouldBe PNG_SIGNATURE
        verify(exactly = 0) { revision.content }
    }

    @Test
    fun `contentBytes falls back to encoding text for a text-only revision`() {
        val revision = mockk<ContentRevision> { every { content } returns "merged\n" }

        revision.contentBytes() shouldBe "merged\n".toByteArray()
    }

    @Test
    fun `contentBytes is null when the revision has no content`() {
        val revision = mockk<ContentRevision> { every { content } returns null }

        revision.contentBytes() shouldBe null
    }

    @Test
    fun `contentBytes is null, not a throw, when loading fails`() {
        val revision = mockk<ByteBackedContentRevision> {
            every { contentAsBytes } throws VcsException("jj file show failed")
            every { file } returns path("img/logo.png")
            every { revisionNumber } returns VcsRevisionNumber.NULL
        }

        revision.contentBytes() shouldBe null
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
