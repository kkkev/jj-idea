package `in`.kkkev.jjidea.ui.log.bookmarks

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ClosestBookmarks
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RepositoryReferences
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.TagItem
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [buildBookmarkTree] — the pure `/`-hierarchy builder behind the bookmarks panel
 * (jj-idea-b2ae / GitHub #48). Kept unit-testable (no platform test needed) per the codebase's
 * convention of extracting pure functions like [`in.kkkev.jjidea.actions.bookmark.bookmarkWidgetText`].
 */
class BookmarkTreeModelTest {
    private val repo = mockk<JujutsuRepository> { every { displayName } returns "repo" }
    private val otherRepo = mockk<JujutsuRepository> { every { displayName } returns "other" }
    private val changeId = ChangeId("qpvuntsm", "qp", 2)

    private fun item(name: String) = BookmarkItem(Bookmark(name), changeId)

    private fun refs(vararg names: String) = mapOf(repo to RepositoryReferences(bookmarks = names.map { item(it) }))

    @Test
    fun `empty repo has no nodes`() {
        buildBookmarkTree(refs(), emptyMap(), emptyMap()) shouldBe emptyList()
    }

    @Test
    fun `single-segment names stay flat under Local`() {
        val tree = buildBookmarkTree(refs("main", "release"), emptyMap(), emptyMap())

        val local = tree.single() as BookmarkNode.Category
        local.displayName shouldBe "Local"
        local.refKind shouldBe RefKind.BOOKMARK
        local.children.map { it.displayName } shouldBe listOf("main", "release")
    }

    @Test
    fun `slash-separated names group into a shared prefix node`() {
        val tree = buildBookmarkTree(refs("feature/A", "feature/B", "fix/C"), emptyMap(), emptyMap())

        val local = tree.single() as BookmarkNode.Category
        val prefixes = local.children.filterIsInstance<BookmarkNode.Prefix>().associateBy { it.displayName }
        prefixes.keys shouldBe setOf("feature", "fix")
        prefixes.getValue("feature").refKind shouldBe RefKind.BOOKMARK
        prefixes.getValue("feature").children.map { it.displayName } shouldBe listOf("A", "B")
        prefixes.getValue("fix").children.map { it.displayName } shouldBe listOf("C")
    }

    @Test
    fun `groups nest recursively for multiple slash segments`() {
        val tree = buildBookmarkTree(refs("a/b/c"), emptyMap(), emptyMap())

        val local = tree.single() as BookmarkNode.Category
        val a = local.children.single() as BookmarkNode.Prefix
        a.displayName shouldBe "a"
        val b = a.children.single() as BookmarkNode.Prefix
        b.displayName shouldBe "b"
        val c = b.children.single() as BookmarkNode.Local
        c.displayName shouldBe "c"
    }

    @Test
    fun `remote bookmarks land under a category per remote, grouped on name`() {
        val references = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(item("main"), item("feature/A@origin"), item("feature/B@origin"))
            )
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())

        val origin = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "origin" }
        val feature = origin.children.single() as BookmarkNode.Prefix
        feature.children.map { it.displayName } shouldBe listOf("A", "B")
        (feature.children.first() as BookmarkNode.Remote).item.bookmark.name.name shouldBe "feature/A@origin"
    }

    @Test
    fun `tags land under their own category, also slash-grouped`() {
        val references = mapOf(
            repo to RepositoryReferences(tags = listOf(TagItem(Tag("v1/rc1"), changeId)))
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())

        val tags = tree.single() as BookmarkNode.Category
        tags.displayName shouldBe "Tags"
        tags.refKind shouldBe RefKind.TAG
        val v1 = tags.children.single() as BookmarkNode.Prefix
        v1.refKind shouldBe RefKind.TAG
        (v1.children.single() as BookmarkNode.Tag).displayName shouldBe "rc1"
    }

    @Test
    fun `prefix groups sort before leaves, alphabetically within each bucket`() {
        val tree = buildBookmarkTree(refs("zeta", "feature/A", "alpha"), emptyMap(), emptyMap())

        val local = tree.single() as BookmarkNode.Category
        local.children.map { it.displayName } shouldBe listOf("feature", "alpha", "zeta")
    }

    @Test
    fun `single repo produces no repo-level wrapper`() {
        val tree = buildBookmarkTree(refs("main"), emptyMap(), emptyMap())

        tree.none { it is BookmarkNode.RepoGroup } shouldBe true
    }

    @Test
    fun `multi-repo wraps each repo's nodes in a RepoGroup`() {
        val references = mapOf(
            repo to RepositoryReferences(bookmarks = listOf(item("main"))),
            otherRepo to RepositoryReferences(bookmarks = listOf(item("main")))
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())

        tree.map { it.displayName } shouldBe listOf("other", "repo")
        tree.all { it is BookmarkNode.RepoGroup } shouldBe true
    }

    @Test
    fun `working copy node label matches bookmarkWidgetText`() {
        val commitId = CommitId("abc123def456", "ab")
        val wcEntry = LogEntry(
            repo = repo,
            id = changeId,
            commitId = commitId,
            underlyingDescription = "",
            bookmarks = listOf(Bookmark("main"))
        )

        val tree = buildBookmarkTree(refs("main"), mapOf(repo to wcEntry), emptyMap())

        val wc = tree.first() as BookmarkNode.WorkingCopy
        wc.displayName shouldBe "main"
    }

    @Test
    fun `working copy node uses the closest-ancestor fallback when nothing sits on it`() {
        val commitId = CommitId("abc123def456", "ab")
        val wcEntry = LogEntry(repo = repo, id = changeId, commitId = commitId, underlyingDescription = "")
        val closest = ClosestBookmarks(listOf(Bookmark("main").name), distance = 3, distanceCapped = false)

        val tree = buildBookmarkTree(refs("main"), mapOf(repo to wcEntry), mapOf(repo to closest))

        val wc = tree.first() as BookmarkNode.WorkingCopy
        wc.displayName shouldBe "main +3"
    }

    @Test
    fun `working copy node is absent when there is nothing to show`() {
        val tree = buildBookmarkTree(refs(), emptyMap(), emptyMap())

        tree.none { it is BookmarkNode.WorkingCopy } shouldBe true
    }

    @Test
    fun `local bookmark on the working copy is marked onWorkingCopy`() {
        val commitId = CommitId("abc123def456", "ab")
        val wcEntry = LogEntry(
            repo = repo,
            id = changeId,
            commitId = commitId,
            underlyingDescription = "",
            bookmarks = listOf(Bookmark("main"))
        )

        val tree = buildBookmarkTree(refs("main", "other"), mapOf(repo to wcEntry), emptyMap())

        val local = tree.filterIsInstance<BookmarkNode.Category>().single()
        val leaves = local.children.filterIsInstance<BookmarkNode.Local>().associateBy { it.displayName }
        leaves.getValue("main").onWorkingCopy shouldBe true
        leaves.getValue("other").onWorkingCopy shouldBe false
    }
}
