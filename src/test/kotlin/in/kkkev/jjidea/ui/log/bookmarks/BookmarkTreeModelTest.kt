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
import io.kotest.matchers.collections.shouldHaveSize
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

    private fun item(
        name: String,
        tracked: Boolean = true,
        conflict: Boolean = false,
        aheadCount: Int = 0,
        behindCount: Int = 0,
        deleted: Boolean = false
    ) = BookmarkItem(
        Bookmark(
            name,
            tracked = tracked,
            deleted = deleted,
            conflict = conflict,
            aheadCount = aheadCount,
            behindCount = behindCount
        ),
        id = if (deleted) null else changeId
    )

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
    fun `the reserved git pseudo-remote is hidden, but the same bookmark still shows under a real remote`() {
        // jj-idea-j0zv, GitHub #48: "git" is jj's own view of a colocated repo's local Git refs,
        // not a peer of real remotes like "origin" - every bookmark would otherwise appear a
        // third time.
        val references = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(item("main"), item("main@git"), item("main@origin"))
            )
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())

        val categories = tree.filterIsInstance<BookmarkNode.Category>().map { it.displayName }
        categories shouldBe listOf("Local", "origin")
        val origin = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "origin" }
        (origin.children.single() as BookmarkNode.Remote).item.bookmark.name.name shouldBe "main@origin"
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

    // jj-idea-we1n / jj-idea-5r0g (GitHub #110): a local bookmark's own aheadCount/behindCount
    // always arrive 0/0 from the CLI template (it can't ask jj about a local ref's tracking
    // counts directly), so buildRepoNodes derives them from the already-fetched remote-tracking
    // rows sharing the same localName; a conflicted local bookmark must also survive into the
    // tree rather than being dropped.

    @Test
    fun `local bookmark's aheadCount and behindCount are derived from its remote's, swapped`() {
        // jj reports tracking counts from the *remote's* perspective (e.g. "@origin (behind by 1
        // commits)" for a local bookmark that is 1 commit ahead), hence the swap.
        val references = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(item("main"), item("main@origin", behindCount = 1))
            )
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val local = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "Local" }
        val main = local.children.filterIsInstance<BookmarkNode.Local>().single()

        main.item.bookmark.aheadCount shouldBe 1
        main.item.bookmark.behindCount shouldBe 0
    }

    @Test
    fun `local bookmark divergence ignores the git pseudo-remote and untracked remotes`() {
        val references = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(
                    item("main"),
                    item("main@git", aheadCount = 5, behindCount = 5),
                    item("main@origin", tracked = false, aheadCount = 9, behindCount = 9)
                )
            )
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val local = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "Local" }
        val main = local.children.filterIsInstance<BookmarkNode.Local>().single()

        main.item.bookmark.aheadCount shouldBe 0
        main.item.bookmark.behindCount shouldBe 0
    }

    @Test
    fun `local bookmark divergence takes the max across multiple tracked remotes, not the sum`() {
        val references = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(
                    item("main"),
                    item("main@origin", behindCount = 2),
                    item("main@github", behindCount = 5)
                )
            )
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val local = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "Local" }
        val main = local.children.filterIsInstance<BookmarkNode.Local>().single()

        main.item.bookmark.aheadCount shouldBe 5
    }

    @Test
    fun `a conflicted local bookmark appears under Local and rolls up as unsynced`() {
        val references = mapOf(repo to RepositoryReferences(bookmarks = listOf(item("main", conflict = true))))

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val local = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "Local" }
        val main = local.children.filterIsInstance<BookmarkNode.Local>().single()

        main.item.bookmark.conflict shouldBe true
        local.rollup.hasUnsynced shouldBe true
    }

    // jj-idea-lc43 (GitHub #110): when a local bookmark is pending deletion (`jj bookmark delete`)
    // but its remote-tracking row still exists, jj reports that row's tracking_ahead_count as the
    // distance from the absent local to the repo root - a huge, meaningless number - rather than
    // the real divergence. Both the remote leaf and the struck-through local leaf's *derived*
    // divergence must be zeroed instead of showing or propagating that garbage.

    @Test
    fun `a remote row whose local is pending-deletion has its counts zeroed`() {
        val references = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(item("foo", deleted = true), item("foo@origin", aheadCount = 1000))
            )
        )

        val origin = buildBookmarkTree(references, emptyMap(), emptyMap())
            .filterIsInstance<BookmarkNode.Category>().single { it.displayName == "origin" }
        val remote = origin.children.filterIsInstance<BookmarkNode.Remote>().single()

        remote.item.bookmark.aheadCount shouldBe 0
        remote.item.bookmark.behindCount shouldBe 0
    }

    @Test
    fun `the struck-through local leaf derives zero divergence, not the swapped garbage count`() {
        val references = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(item("foo", deleted = true), item("foo@origin", aheadCount = 1000))
            )
        )

        val local = buildBookmarkTree(references, emptyMap(), emptyMap())
            .filterIsInstance<BookmarkNode.Category>().single { it.displayName == "Local" }
        val foo = local.children.filterIsInstance<BookmarkNode.Local>().single()

        foo.item.bookmark.deleted shouldBe true
        foo.item.bookmark.aheadCount shouldBe 0
        foo.item.bookmark.behindCount shouldBe 0
    }

    @Test
    fun `a pending-deletion row alone does not make its remote category notable`() {
        val references = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(item("foo", deleted = true), item("foo@origin", aheadCount = 1000))
            )
        )

        val origin = buildBookmarkTree(references, emptyMap(), emptyMap())
            .filterIsInstance<BookmarkNode.Category>().single { it.displayName == "origin" }

        origin.rollup.isNotable shouldBe false
    }

    @Test
    fun `a live sibling bookmark on the same remote keeps its real counts`() {
        val references = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(
                    item("foo", deleted = true),
                    item("foo@origin", aheadCount = 1000),
                    item("bar"),
                    item("bar@origin", behindCount = 3)
                )
            )
        )

        val origin = buildBookmarkTree(references, emptyMap(), emptyMap())
            .filterIsInstance<BookmarkNode.Category>().single { it.displayName == "origin" }
        val remotes = origin.children.filterIsInstance<BookmarkNode.Remote>().associateBy { it.displayName }

        remotes.getValue("foo").item.bookmark.aheadCount shouldBe 0
        remotes.getValue("bar").item.bookmark.behindCount shouldBe 3
        origin.rollup.aheadCount shouldBe 0
        origin.rollup.behindCount shouldBe 3
    }

    @Test
    fun `local divergence lookup is a single grouped pass, not one remoteEntriesFor scan per leaf`() {
        // Operation-count guard (CLAUDE.md Performance & Scale): grouping remotes by localName
        // once (O(B)) rather than filtering the full bookmark list per local leaf (O(B) work per
        // leaf, O(B^2) total) is the point of this fix - sized to make an O(B^2) regression slow
        // while an O(B) pass stays fast.
        val localCount = 3_000
        val bookmarks = (0 until localCount).flatMap { i ->
            listOf(item("main$i"), item("main$i@origin", behindCount = 1))
        }
        val references = mapOf(repo to RepositoryReferences(bookmarks = bookmarks))

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val local = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "Local" }
        val leaves = local.children.filterIsInstance<BookmarkNode.Local>()

        leaves shouldHaveSize localCount
        leaves.forEach { it.item.bookmark.aheadCount shouldBe 1 }
    }

    // jj-idea-a7a7 (GitHub #48): remote categories collapse by default; roll-up counts surface a
    // collapsed category's unsynced/diverged bookmarks.

    @Test
    fun `Local and Tags categories default to expanded, a remote category defaults to collapsed`() {
        val references = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(item("main"), item("main@origin")),
                tags = listOf(TagItem(Tag("v1"), changeId))
            )
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val categories = tree.filterIsInstance<BookmarkNode.Category>().associateBy { it.displayName }

        categories.getValue("Local").defaultExpanded() shouldBe true
        categories.getValue("Tags").defaultExpanded() shouldBe true
        categories.getValue("origin").defaultExpanded() shouldBe false
    }

    @Test
    fun `a remote category rolls up ahead-behind counts and untracked-conflict status from its leaves`() {
        val references = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(
                    item("clean@origin"),
                    item("ahead@origin", aheadCount = 2),
                    item("behind@origin", behindCount = 3),
                    item("untracked@origin", tracked = false)
                )
            )
        )

        val origin = buildBookmarkTree(references, emptyMap(), emptyMap())
            .filterIsInstance<BookmarkNode.Category>().single { it.displayName == "origin" }

        origin.rollup.aheadCount shouldBe 2
        origin.rollup.behindCount shouldBe 3
        origin.rollup.hasUnsynced shouldBe true
        origin.rollup.isNotable shouldBe true
    }

    @Test
    fun `a category with only clean tracked bookmarks has an empty, non-notable rollup`() {
        val references = mapOf(repo to RepositoryReferences(bookmarks = listOf(item("main@origin"))))

        val origin = buildBookmarkTree(references, emptyMap(), emptyMap())
            .filterIsInstance<BookmarkNode.Category>().single { it.displayName == "origin" }

        origin.rollup shouldBe BookmarkRollup.EMPTY
        origin.rollup.isNotable shouldBe false
    }

    @Test
    fun `rollup propagates up through nested prefix groups to the owning category`() {
        val references = mapOf(
            repo to RepositoryReferences(bookmarks = listOf(item("feature/a@origin", aheadCount = 1)))
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val origin = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "origin" }
        val feature = origin.children.single() as BookmarkNode.Prefix

        feature.rollup.aheadCount shouldBe 1
        origin.rollup.aheadCount shouldBe 1
    }

    @Test
    fun `rollup aggregation is a single O(B) fold, not a re-scan per node`() {
        // Operation-count guard (CLAUDE.md Performance & Scale): a regression that re-walks every
        // descendant leaf from each ancestor Category/Prefix, instead of folding each level's own
        // already-computed child rollups (see buildBookmarkTree's KDoc), would still pass a
        // correctness check at small N but is exactly what this larger, flat-and-nested mix of
        // bookmark counts is sized to make slow.
        val flatCount = 2_000
        val nestedCount = 2_000
        val flatDiverged = (0 until flatCount).map { i ->
            item("flat$i@origin", aheadCount = if (i % 2 == 0) 1 else 0)
        }
        val nestedDiverged = (0 until nestedCount).map { i ->
            item("group/nested$i@origin", behindCount = if (i % 2 == 0) 1 else 0)
        }
        val references = mapOf(repo to RepositoryReferences(bookmarks = flatDiverged + nestedDiverged))

        val origin = buildBookmarkTree(references, emptyMap(), emptyMap())
            .filterIsInstance<BookmarkNode.Category>().single { it.displayName == "origin" }
        val group = origin.children.filterIsInstance<BookmarkNode.Prefix>().single { it.displayName == "group" }

        // Every other bookmark in each half is diverged by 1, so the totals are exactly half of
        // each half's count - correct only if every leaf is counted exactly once, at exactly one
        // level of aggregation each (leaf -> Prefix -> Category, no double-counting).
        group.rollup.behindCount shouldBe nestedCount / 2
        origin.rollup.aheadCount shouldBe flatCount / 2
        origin.rollup.behindCount shouldBe nestedCount / 2
        origin.children.size shouldBe flatCount + 1 // flatCount leaves + the one "group" Prefix
    }

    @Test
    fun `expansionPathKey joins a node's ancestry by displayName`() {
        val references = mapOf(
            repo to RepositoryReferences(bookmarks = listOf(item("feature/a@origin")))
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val origin = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "origin" }
        val feature = origin.children.single() as BookmarkNode.Prefix

        val originKey = origin.expansionPathKey("")
        originKey shouldBe "origin"
        feature.expansionPathKey(originKey) shouldBe "origin/feature"
    }
}
