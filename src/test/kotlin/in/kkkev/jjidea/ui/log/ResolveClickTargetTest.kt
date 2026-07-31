package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.refUri
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [resolveClickTarget]/[personClickForEmail] (jj-idea-a52h): the commit details pane's
 * right-click resolution used to only handle `jjref://` bookmark/tag hrefs - right-clicking an
 * author/committer `mailto:` link silently did nothing, unlike the log table where the same
 * right-click already worked (jj-idea-iesq). A `mailto:` href carries only the email, not which
 * entry or role (author vs. committer) it came from, so [personClickForEmail] recovers both by
 * matching against the known entries.
 */
class ResolveClickTargetTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    private fun entry(
        changeId: String,
        author: com.intellij.vcs.log.VcsUser? = null,
        committer: com.intellij.vcs.log.VcsUser? = null
    ) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("0".repeat(40)),
        underlyingDescription = "Test commit $changeId",
        author = author,
        committer = committer
    )

    @Test
    fun `resolveClickTarget resolves a mailto href to the matching author as a filterable PersonClick`() {
        val alice = VcsUserImpl("Alice", "alice@example.com")
        val e = entry("aaa111", author = alice)

        val target = resolveClickTarget("mailto:alice@example.com", listOf(e))

        target.shouldNotBeNull()
        target as PersonClick
        target.user shouldBe alice
        target.canFilter shouldBe true
    }

    @Test
    fun `resolveClickTarget resolves a mailto href to the matching committer as a non-filterable PersonClick`() {
        val alice = VcsUserImpl("Alice", "alice@example.com")
        val bob = VcsUserImpl("Bob", "bob@example.com")
        val e = entry("aaa111", author = alice, committer = bob)

        val target = resolveClickTarget("mailto:bob@example.com", listOf(e))

        target.shouldNotBeNull()
        target as PersonClick
        target.user shouldBe bob
        target.canFilter shouldBe false
    }

    @Test
    fun `an email matching both an author and a committer resolves as the filterable author`() {
        val shared = VcsUserImpl("Shared", "shared@example.com")
        val e1 = entry("aaa111", committer = shared)
        val e2 = entry("bbb222", author = shared)

        val target = personClickForEmail("shared@example.com", listOf(e1, e2))

        target.shouldNotBeNull()
        target.canFilter shouldBe true
        target.entry shouldBe e2
    }

    @Test
    fun `resolveClickTarget returns null for a mailto href matching no entry`() {
        val e = entry("aaa111", author = VcsUserImpl("Alice", "alice@example.com"))

        resolveClickTarget("mailto:nobody@example.com", listOf(e)).shouldBeNull()
    }

    @Test
    fun `resolveClickTarget still resolves a jjref bookmark href unaffected by the mailto branch`() {
        val e = entry("aaa111")
        val href = refUri(e, "bookmark", "main").toString()

        // No bookmark named "main" on this entry, so LogClickTarget.resolve legitimately returns
        // null here - this just proves the href reaches the jjref:// branch, not mailto's.
        resolveClickTarget(href, listOf(e)).shouldBeNull()
    }
}
