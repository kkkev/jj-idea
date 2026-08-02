package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.refUri
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Tests for [LogClickTarget.resolve] (jj-idea-91qf, jj-idea-vrmv, and the link-dispatch-unification
 * follow-up): the single place that interprets every link scheme jj-idea emits. `jjref://`
 * bookmark hrefs and `mailto:` author/committer hrefs are looked up against the given entries
 * (this file used to be split across `LogClickTarget.resolve`, `JujutsuCommitDetailsPanel
 * .resolveClickTarget`, and `.personClickForEmail` - now all in one place); `http(s)://` resolves
 * to [IssueLinkClick] without needing an entry lookup at all. `jjc://` (change-ID navigation) needs
 * a real [com.intellij.openapi.project.Project] to resolve a repository from its embedded path, so
 * isn't covered by these pure-unit tests - resolving it with `project = null` (as these tests do
 * throughout, since none of them exercise jjc) always yields null, verified below.
 */
class LogClickTargetTest {
    // A relaxed mock's VirtualFile.path defaults to "", which collapses the "jjref://<host>?..."
    // authority to nothing and breaks LogClickTarget.REF_URL_PARSER's `([^?]+)` host group - stub
    // a real path so refUri produces a URI that actually round-trips through the regex.
    private val repo = mockk<JujutsuRepository>(relaxed = true).also { every { it.directory.path } returns "/repo" }

    private fun entry(
        bookmarks: List<Bookmark> = emptyList(),
        changeId: String = "qpvuntsm",
        author: com.intellij.vcs.log.VcsUser? = null,
        committer: com.intellij.vcs.log.VcsUser? = null
    ) = LogEntry(
        repo = repo,
        id = ChangeId(changeId, changeId, null),
        commitId = CommitId("abc123def456"),
        underlyingDescription = "Test commit",
        bookmarks = bookmarks,
        author = author,
        committer = committer
    )

    private fun resolve(uri: URI, vararg entries: LogEntry) =
        LogClickTarget.resolve(uri, project = null, entries.toList())

    @Test
    fun `an http URI resolves to an IssueLinkClick carrying that URI`() {
        val uri = URI("https://tracker/JIRA-123")

        val target = resolve(uri)

        target.shouldNotBeNull()
        target as IssueLinkClick
        target.uri shouldBe uri
    }

    @Test
    fun `an https URI resolves to an IssueLinkClick regardless of any jjref-shaped query`() {
        val uri = URI("https://tracker/path?kind=bookmark&name=main")

        val target = resolve(uri)

        target.shouldNotBeNull()
        target as IssueLinkClick
        target.uri shouldBe uri
    }

    @Test
    fun `a jjref bookmark URI still resolves to a BookmarkClick, unaffected by the http branch`() {
        val bookmark = Bookmark("main")
        val e = entry(listOf(bookmark))
        val uri = refUri(e, "bookmark", "main")

        val target = resolve(uri, e)

        target.shouldNotBeNull()
        target as BookmarkClick
        target.bookmark shouldBe bookmark
    }

    @Test
    fun `a jjref URI naming an unknown bookmark resolves to null`() {
        val e = entry()
        val uri = refUri(e, "bookmark", "no-such-bookmark")

        resolve(uri, e).shouldBeNull()
    }

    @Test
    fun `a jjc URI resolves to null without a project`() {
        val uri = URI("jjc://repo?qpvuntsm")

        resolve(uri).shouldBeNull()
    }

    @Test
    fun `a mailto URI resolves to the matching author as a filterable PersonClick`() {
        val alice = VcsUserImpl("Alice", "alice@example.com")
        val e = entry(changeId = "aaa111", author = alice)

        val target = resolve(URI("mailto", "alice@example.com", null), e)

        target.shouldNotBeNull()
        target as PersonClick
        target.user shouldBe alice
        target.canFilter shouldBe true
    }

    @Test
    fun `a mailto URI resolves to the matching committer as a non-filterable PersonClick`() {
        val alice = VcsUserImpl("Alice", "alice@example.com")
        val bob = VcsUserImpl("Bob", "bob@example.com")
        val e = entry(changeId = "aaa111", author = alice, committer = bob)

        val target = resolve(URI("mailto", "bob@example.com", null), e)

        target.shouldNotBeNull()
        target as PersonClick
        target.user shouldBe bob
        target.canFilter shouldBe false
    }

    @Test
    fun `an email matching both an author and a committer resolves as the filterable author`() {
        val shared = VcsUserImpl("Shared", "shared@example.com")
        val e1 = entry(changeId = "aaa111", committer = shared)
        val e2 = entry(changeId = "bbb222", author = shared)

        val target = resolve(URI("mailto", "shared@example.com", null), e1, e2)

        target.shouldNotBeNull()
        target as PersonClick
        target.canFilter shouldBe true
        target.entry shouldBe e2
    }

    @Test
    fun `a mailto URI matching no entry resolves to null`() {
        val e = entry(changeId = "aaa111", author = VcsUserImpl("Alice", "alice@example.com"))

        resolve(URI("mailto", "nobody@example.com", null), e).shouldBeNull()
    }
}
