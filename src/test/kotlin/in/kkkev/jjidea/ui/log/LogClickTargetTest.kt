package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.refUri
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Tests for [LogClickTarget.resolve] (jj-idea-91qf, jj-idea-vrmv): `jjref://` bookmark/tag hrefs
 * keep resolving as before; `http(s)://` hrefs - a linkified issue-tracker reference, e.g.
 * `JIRA-123` linkified by [in.kkkev.jjidea.ui.components.appendLinkified] - now resolve to
 * [IssueLinkClick] without needing an entry lookup.
 */
class LogClickTargetTest {
    // A relaxed mock's VirtualFile.path defaults to "", which collapses the "jjref://<host>?..."
    // authority to nothing and breaks LogClickTarget.REF_URL_PARSER's `([^?]+)` host group - stub
    // a real path so refUri produces a URI that actually round-trips through the regex.
    private val repo = mockk<JujutsuRepository>(relaxed = true).also { every { it.directory.path } returns "/repo" }

    private fun entry(bookmarks: List<Bookmark> = emptyList()) = LogEntry(
        repo = repo,
        id = ChangeId("qpvuntsm", "qp", 2),
        commitId = CommitId("abc123def456"),
        underlyingDescription = "Test commit",
        bookmarks = bookmarks
    )

    @Test
    fun `an http URI resolves to an IssueLinkClick carrying that URI`() {
        val e = entry()
        val uri = URI("https://tracker/JIRA-123")

        val target = LogClickTarget.resolve(uri, e)

        target.shouldNotBeNull()
        target as IssueLinkClick
        target.uri shouldBe uri
        target.entry shouldBe e
    }

    @Test
    fun `an https URI resolves to an IssueLinkClick regardless of any jjref-shaped query`() {
        val e = entry()
        val uri = URI("https://tracker/path?kind=bookmark&name=main")

        val target = LogClickTarget.resolve(uri, e)

        target.shouldNotBeNull()
        target as IssueLinkClick
        target.uri shouldBe uri
    }

    @Test
    fun `a jjref bookmark URI still resolves to a BookmarkClick, unaffected by the http branch`() {
        val bookmark = Bookmark("main")
        val e = entry(listOf(bookmark))
        val uri = refUri(e, "bookmark", "main")

        val target = LogClickTarget.resolve(uri, e)

        target.shouldNotBeNull()
        target as BookmarkClick
        target.bookmark shouldBe bookmark
    }

    @Test
    fun `a jjref URI naming an unknown bookmark resolves to null`() {
        val e = entry()
        val uri = refUri(e, "bookmark", "no-such-bookmark")

        LogClickTarget.resolve(uri, e).shouldBeNull()
    }
}
