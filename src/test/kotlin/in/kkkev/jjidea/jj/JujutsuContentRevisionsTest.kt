package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
        val repo = mockRepo
        val filePath = path("src/Main.kt")

        val first = ContentLogEntryImpl(repo, filePath, changeIdA)
        val second = ContentLogEntryImpl(repo, filePath, changeIdA)

        first shouldBe second
        first.hashCode() shouldBe second.hashCode()
    }

    @Test
    fun `ContentLogEntryImpl instances differ when the change id differs`() {
        val repo = mockRepo
        val filePath = path("src/Main.kt")

        ContentLogEntryImpl(repo, filePath, changeIdA) shouldNotBe ContentLogEntryImpl(repo, filePath, changeIdB)
    }

    @Test
    fun `ContentLogEntryImpl instances differ when the path differs`() {
        val repo = mockRepo

        ContentLogEntryImpl(repo, path("src/A.kt"), changeIdA) shouldNotBe
            ContentLogEntryImpl(repo, path("src/B.kt"), changeIdA)
    }

    @Test
    fun `MergeParentContentRevision instances are equal for the same repo, path and merge parent`() {
        val repo = mockRepo
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

        val logEntryRevision = ContentLogEntryImpl(mockRepo, filePath, changeIdA)

        logEntryRevision.shouldBeInstanceOf<ContentLogEntryImpl>()
        (logEntryRevision == CurrentContentRevision(filePath)) shouldBe false
    }
}
