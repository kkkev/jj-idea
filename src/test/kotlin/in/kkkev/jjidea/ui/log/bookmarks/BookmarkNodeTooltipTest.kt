package `in`.kkkev.jjidea.ui.log.bookmarks

import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ChangeKey
import `in`.kkkev.jjidea.jj.ClosestBookmarks
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.TagItem
import `in`.kkkev.jjidea.ui.components.UNBREAKABLE_PREFIX
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [bookmarkNodeTooltip] v2 (jj-idea-uyu9, GitHub #110), rewritten after user feedback
 * on the first cut: no more restating what the row's own icon/arrows already show, no beginner
 * prose - every tooltip shows only its `[GroupLabel, ...]` bracket, its full `/`-qualified path,
 * and (when resolvable) the commit it points to. Kept unit-testable (no platform test needed),
 * matching [BookmarkTreeModelTest] and [in.kkkev.jjidea.ui.components.RevisionChoiceTooltipTest].
 */
class BookmarkNodeTooltipTest {
    private fun fakeRepo(name: String, dirPath: String): JujutsuRepository {
        val dir = mockk<VirtualFile> { every { path } returns dirPath }
        return mockk {
            every { displayName } returns name
            every { directory } returns dir
        }
    }

    private val repo = fakeRepo("repo", "/repo")
    private val otherRepo = fakeRepo("other", "/other")
    private val changeId = ChangeId("qpvuntsm", "qp", 2)
    private val entry = LogEntry(
        repo = repo,
        id = changeId,
        commitId = CommitId("abc123def456", "ab"),
        underlyingDescription = "Fix the thing"
    )
    private val entryLookup: (ChangeKey) -> LogEntry? = { key -> entry.takeIf { key == ChangeKey(repo, changeId) } }
    private val noEntry: (ChangeKey) -> LogEntry? = { null }

    private fun bookmark(
        name: String,
        tracked: Boolean = true,
        deleted: Boolean = false,
        conflict: Boolean = false,
        aheadCount: Int = 0,
        behindCount: Int = 0
    ) = Bookmark(
        name,
        tracked = tracked,
        deleted = deleted,
        conflict = conflict,
        aheadCount = aheadCount,
        behindCount = behindCount
    )

    private fun local(bookmark: Bookmark, onWorkingCopy: Boolean = false) =
        BookmarkNode.Local(repo, BookmarkItem(bookmark, changeId), bookmark.name.localName, onWorkingCopy)

    private fun remote(bookmark: Bookmark) =
        BookmarkNode.Remote(repo, BookmarkItem(bookmark, changeId), bookmark.name.localName)

    /**
     * The tooltip HTML, decoded for readable assertions: [appendBracket]'s comma separator uses
     * [TextCanvas.space] (a non-collapsing `&nbsp;`, not a literal space - see its doc), and a
     * chip's label text is itself URL-encoded as part of its
     * [in.kkkev.jjidea.ui.components.UnbreakableContent] payload - neither matters for how the
     * tooltip actually renders, but both would otherwise make substring assertions here brittle.
     * Only the `unbreakable:...'` payload is URL-decoded (a blanket decode of the whole string
     * trips over unrelated literal `%` characters, e.g. `font-size: 85%`).
     */
    private fun tooltip(
        node: BookmarkNode,
        entryLookup: (ChangeKey) -> LogEntry? = noEntry,
        isMultiRepo: Boolean = false
    ) = bookmarkNodeTooltip(node, entryLookup, isMultiRepo)
        ?.replace("&nbsp;", " ")
        ?.let { html ->
            Regex("unbreakable:([^'\"]+)").replace(html) { m ->
                java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
            }
        }

    // -- chips render through the icon-aware machinery (jj-idea-fmrj/jj-idea-2md7) --

    @Test
    fun `local bookmark tooltip carries an unbreakable chip and its group bracket`() {
        // Raw (undecoded) markup here, matching RevisionChoiceTooltipTest's regression coverage
        // for the same hazard - tooltip()'s decoding (above) is for readable text assertions, not
        // for checking the wire format itself.
        val raw = bookmarkNodeTooltip(local(bookmark("main")), noEntry, isMultiRepo = false)
        raw.shouldNotBeNull()
        raw shouldContain "<img"
        raw shouldContain UNBREAKABLE_PREFIX

        val html = tooltip(local(bookmark("main")))
        html.shouldNotBeNull()
        html shouldContain "[Local]"
    }

    @Test
    fun `local bookmark shows its full slash-qualified path, not just the last segment`() {
        val html = tooltip(local(bookmark("branches/foo/bar")))

        html.shouldNotBeNull()
        html shouldContain "branches/foo/bar"
    }

    @Test
    fun `local bookmark tooltip no longer spells out ahead-behind-conflict-deleted as prose`() {
        // The old verbose sentences are gone (jj-idea-uyu9 v2) - the chip's own icon/strikethrough/
        // arrows already convey this, same as the row itself.
        val html = tooltip(local(bookmark("main", aheadCount = 2, conflict = true, deleted = true)))

        html.shouldNotBeNull()
        html shouldNotContain "ahead of"
        html shouldNotContain "conflict"
        html shouldNotContain "Pending deletion"
    }

    @Test
    fun `remote bookmark group bracket names the actual remote`() {
        val html = tooltip(remote(bookmark("main@origin")))

        html.shouldNotBeNull()
        html shouldContain "[origin]"
        html shouldContain "main"
        html shouldNotContain "main@origin"
    }

    @Test
    fun `remote bookmark behind its remote flags a force-push`() {
        val html = tooltip(remote(bookmark("main@origin", behindCount = 1)))

        html.shouldNotBeNull()
        html shouldContain "force-push required"
    }

    @Test
    fun `remote bookmark ahead-only doesn't need a force-push`() {
        val html = tooltip(remote(bookmark("main@origin", aheadCount = 1)))

        html.shouldNotBeNull()
        html shouldNotContain "force-push"
    }

    // -- per-remote breakdown on a local bookmark (jj-idea-uyu9 follow-up) --

    @Test
    fun `local bookmark tracked by one remote shows that remote's status`() {
        val node = local(bookmark("main")).copy(
            remotes = listOf(bookmark("main@origin", aheadCount = 2))
        )

        val html = tooltip(node)

        html.shouldNotBeNull()
        html shouldContain "[origin, ↑2]"
    }

    @Test
    fun `local bookmark tracked by multiple remotes shows each remote on its own line`() {
        val node = local(bookmark("main")).copy(
            remotes = listOf(bookmark("main@origin"), bookmark("main@github", behindCount = 1))
        )

        val html = tooltip(node)

        html.shouldNotBeNull()
        html shouldContain "[origin, in sync]"
        html shouldContain "[github, ↓1, force-push required]"
    }

    @Test
    fun `local bookmark with no tracked remotes shows no per-remote breakdown`() {
        val html = tooltip(local(bookmark("main")))

        html.shouldNotBeNull()
        html shouldNotContain "in sync"
        html shouldNotContain "force-push"
    }

    @Test
    fun `tag tooltip has no redundant Tag label`() {
        val node = BookmarkNode.Tag(repo, TagItem(Tag("release/v1.0"), changeId), "v1.0")

        val html = tooltip(node)

        html.shouldNotBeNull()
        html shouldContain "[Tags]"
        html shouldContain "release/v1.0"
        html shouldNotContain ">Tag<"
    }

    @Test
    fun `tag tooltip includes its target commit's info, same as a bookmark's`() {
        val node = BookmarkNode.Tag(repo, TagItem(Tag("v1.0"), changeId), "v1.0")

        val html = tooltip(node, entryLookup = entryLookup)

        html.shouldNotBeNull()
        html shouldContain "qp"
        html shouldContain "Fix the thing"
    }

    // -- commit info block (DRY with the log's own row tooltip via appendChangeTooltip) --

    @Test
    fun `commit info is included when the target change resolves`() {
        val html = tooltip(local(bookmark("main")), entryLookup = entryLookup)

        html.shouldNotBeNull()
        html shouldContain "qp"
        html shouldContain "Fix the thing"
    }

    @Test
    fun `commit info is silently omitted when the target change is outside the loaded window`() {
        val html = tooltip(local(bookmark("main")), entryLookup = noEntry)

        html.shouldNotBeNull()
        html shouldNotContain "Fix the thing"
    }

    // -- folder (Category/Prefix) tooltips --

    @Test
    fun `a quiet category bracket names only the group, no extra noise`() {
        val category = BookmarkNode.Category(repo, "Local", RefKind.BOOKMARK, emptyList())

        val html = tooltip(category)

        html.shouldNotBeNull()
        html shouldContain "[Local]"
    }

    @Test
    fun `a notable category bracket appends divergence and unsynced counts`() {
        val category = BookmarkNode.Category(
            repo,
            "origin",
            RefKind.BOOKMARK,
            emptyList(),
            rollup = BookmarkRollup(aheadCount = 2, behindCount = 1, unsyncedCount = 3)
        )

        val html = tooltip(category)

        html.shouldNotBeNull()
        html shouldContain "[origin, ↑2↓1, 3 unsynced]"
    }

    @Test
    fun `a prefix bracket names the owning top-level group, not its own segment, plus its path`() {
        val prefix = BookmarkNode.Prefix(
            repo,
            "foo",
            RefKind.BOOKMARK,
            emptyList(),
            groupLabel = "Local",
            fullPath = "branches/foo"
        )

        val html = tooltip(prefix)

        html.shouldNotBeNull()
        html shouldContain "[Local]"
        html shouldNotContain "[foo]"
        html shouldContain "branches/foo"
    }

    @Test
    fun `a category's leaf count is shown, pluralized`() {
        val one = BookmarkNode.Category(
            repo,
            "Local",
            RefKind.BOOKMARK,
            emptyList(),
            rollup = BookmarkRollup(leafCount = 1)
        )
        val many = BookmarkNode.Category(
            repo,
            "Local",
            RefKind.BOOKMARK,
            emptyList(),
            rollup = BookmarkRollup(leafCount = 5)
        )

        tooltip(one).shouldNotBeNull() shouldContain "1 bookmark"
        tooltip(many).shouldNotBeNull() shouldContain "5 bookmarks"
    }

    @Test
    fun `a Tags category's leaf count says tags, not bookmarks`() {
        val category = BookmarkNode.Category(
            repo,
            "Tags",
            RefKind.TAG,
            emptyList(),
            rollup = BookmarkRollup(leafCount = 3)
        )

        tooltip(category).shouldNotBeNull() shouldContain "3 tags"
    }

    @Test
    fun `an empty folder's tooltip has no leaf-count line`() {
        val category = BookmarkNode.Category(repo, "Local", RefKind.BOOKMARK, emptyList())

        val html = tooltip(category)

        html.shouldNotBeNull()
        html shouldNotContain "bookmark"
    }

    @Test
    fun `a prefix also shows its own leaf count`() {
        val prefix = BookmarkNode.Prefix(
            repo,
            "foo",
            RefKind.BOOKMARK,
            emptyList(),
            rollup = BookmarkRollup(leafCount = 2),
            groupLabel = "Local",
            fullPath = "branches/foo"
        )

        tooltip(prefix).shouldNotBeNull() shouldContain "2 bookmarks"
    }

    @Test
    fun `the Unbookmarked heads category gets a compact count sentence instead of a bracket`() {
        val head = BookmarkNode.DanglingHead(repo, changeId, null, "(no bookmark) qp")
        val category = BookmarkNode.Category(
            repo,
            "Unbookmarked heads",
            RefKind.BOOKMARK,
            listOf(head, head),
            isDanglingHeadsGroup = true
        )

        val html = tooltip(category)

        html.shouldNotBeNull()
        html shouldContain "2 heads without bookmarks"
        html shouldNotContain "["
    }

    @Test
    fun `a single Unbookmarked head uses the singular wording`() {
        val head = BookmarkNode.DanglingHead(repo, changeId, null, "(no bookmark) qp")
        val category = BookmarkNode.Category(
            repo,
            "Unbookmarked heads",
            RefKind.BOOKMARK,
            listOf(head),
            isDanglingHeadsGroup = true
        )

        tooltip(category).shouldNotBeNull() shouldContain "1 head without a bookmark"
    }

    // -- working copy row --

    @Test
    fun `working copy on a bookmark directly shows the bookmark, no distance bracket`() {
        val node = BookmarkNode.WorkingCopy(repo, "main", changeId, listOf("main"), closest = null)

        val html = tooltip(node)

        html.shouldNotBeNull()
        html shouldContain "main"
        html shouldNotContain "commits ahead of"
    }

    @Test
    fun `working copy past its nearest bookmark shows the compact distance form`() {
        val closest = ClosestBookmarks(names = listOf(Bookmark("main").name), distance = 3, distanceCapped = false)
        val node = BookmarkNode.WorkingCopy(repo, "main +3", changeId, emptyList(), closest)

        val html = tooltip(node)

        html.shouldNotBeNull()
        html shouldContain "[3 commits ahead of"
        html shouldContain "main"
    }

    @Test
    fun `working copy's compact distance form has no comma before a single bookmark name`() {
        val closest = ClosestBookmarks(names = listOf(Bookmark("main").name), distance = 3, distanceCapped = false)
        val node = BookmarkNode.WorkingCopy(repo, "main +3", changeId, emptyList(), closest)

        val html = tooltip(node)

        html.shouldNotBeNull()
        html shouldNotContain "," // a single name has nothing to comma-separate from
        html shouldContain "[3 commits ahead of"
        html shouldContain "main]"
    }

    @Test
    fun `working copy row includes its own commit info`() {
        val node = BookmarkNode.WorkingCopy(repo, "main", changeId, listOf("main"), closest = null)

        val html = tooltip(node, entryLookup = entryLookup)

        html.shouldNotBeNull()
        html shouldContain "Fix the thing"
    }

    @Test
    fun `working copy row's commit info includes the working-copy status tag`() {
        val wcEntry = entry.copy(isWorkingCopy = true)
        val node = BookmarkNode.WorkingCopy(repo, "main", changeId, listOf("main"), closest = null)

        val html = tooltip(node, entryLookup = { wcEntry })

        html.shouldNotBeNull()
        html shouldContain "Working Copy"
    }

    // -- dangling head --

    @Test
    fun `dangling head with a closest bookmark uses the compact distance form`() {
        val closest = ClosestBookmarks(names = listOf(Bookmark("main").name), distance = 2, distanceCapped = false)
        val node = BookmarkNode.DanglingHead(repo, changeId, closest, "main +2 qp")

        val html = tooltip(node)

        html.shouldNotBeNull()
        html shouldContain "[Unbookmarked heads]"
        html shouldContain "2 commits ahead of"
    }

    @Test
    fun `dangling head bracket carries the slashed-bookmark icon, matching its row and folder icon`() {
        val closest = ClosestBookmarks(names = listOf(Bookmark("main").name), distance = 2, distanceCapped = false)
        val node = BookmarkNode.DanglingHead(repo, changeId, closest, "main +2 qp")

        val html = bookmarkNodeTooltip(node, noEntry, isMultiRepo = false)

        html.shouldNotBeNull()
        html shouldContain "JujutsuIcons.BookmarkNone"
    }

    @Test
    fun `working copy's distance bracket has no dangling-head icon - it isn't one`() {
        val closest = ClosestBookmarks(names = listOf(Bookmark("main").name), distance = 2, distanceCapped = false)
        val node = BookmarkNode.WorkingCopy(repo, "main +2", changeId, emptyList(), closest)

        val html = bookmarkNodeTooltip(node, noEntry, isMultiRepo = false)

        html.shouldNotBeNull()
        html shouldNotContain "JujutsuIcons.BookmarkNone"
    }

    @Test
    fun `dangling head with no ancestor bookmark says so compactly`() {
        val node = BookmarkNode.DanglingHead(repo, changeId, closest = null, "(no bookmark) qp")

        val html = tooltip(node)

        html.shouldNotBeNull()
        html shouldContain "no ancestor bookmark"
    }

    // -- repo line (multi-repo only) --

    @Test
    fun `single-repo project shows no repo line`() {
        val html = tooltip(local(bookmark("main")), isMultiRepo = false)

        html.shouldNotBeNull()
        html shouldNotContain "repo"
    }

    @Test
    fun `multi-repo project leads with a repo line`() {
        val html = tooltip(local(bookmark("main")), isMultiRepo = true)

        html.shouldNotBeNull()
        html shouldContain "repo"
    }

    @Test
    fun `RepoGroup tooltip is just the repo`() {
        val node = BookmarkNode.RepoGroup(otherRepo, emptyList())

        tooltip(node).shouldNotBeNull() shouldContain "other"
    }
}
