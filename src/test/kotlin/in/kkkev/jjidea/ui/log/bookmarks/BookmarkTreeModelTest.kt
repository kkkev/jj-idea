package `in`.kkkev.jjidea.ui.log.bookmarks

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.BookmarkName
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.ClosestBookmarks
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.DanglingHead
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

    // jj-idea-ks5k (GitHub #110): divergence (ahead/behind) derivation, and the deleted-local /
    // absent-remote count corrections, all moved upstream into
    // `LogService.withDerivedDivergence` (see `BookmarkDivergenceTest`/`BookmarkDivergenceScaleTest`)
    // so the panel and the log table's bookmark chips read the same already-derived numbers
    // instead of each computing (or, for the log's local chips, failing to compute) their own.
    // The panel's own job is now just to render whatever [BookmarkItem] it's given - these tests
    // check that pass-through, plus that a conflicted local bookmark still survives into the tree.

    @Test
    fun `renders whatever ahead-behind the given BookmarkItem already carries, without re-deriving`() {
        val references = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(item("main", aheadCount = 1, behindCount = 2), item("main@origin"))
            )
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val local = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "Local" }
        val main = local.children.filterIsInstance<BookmarkNode.Local>().single()

        main.item.bookmark.aheadCount shouldBe 1
        main.item.bookmark.behindCount shouldBe 2
    }

    @Test
    fun `a conflicted local bookmark appears under Local and rolls up as unsynced`() {
        val references = mapOf(repo to RepositoryReferences(bookmarks = listOf(item("main", conflict = true))))

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val local = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "Local" }
        val main = local.children.filterIsInstance<BookmarkNode.Local>().single()

        main.item.bookmark.conflict shouldBe true
        local.rollup.unsyncedCount shouldBe 1
    }

    @Test
    fun `a struck-through pending-deletion leaf renders as given, and doesn't make its remote category notable`() {
        val references = mapOf(
            repo to RepositoryReferences(
                // Already zeroed by withDerivedDivergence upstream - the panel no longer zeroes.
                bookmarks = listOf(item("foo", deleted = true), item("foo@origin", aheadCount = 0, behindCount = 0))
            )
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val local = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "Local" }
        val foo = local.children.filterIsInstance<BookmarkNode.Local>().single()
        val origin = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "origin" }

        foo.item.bookmark.deleted shouldBe true
        origin.rollup.isNotable shouldBe false
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
        origin.rollup.unsyncedCount shouldBe 1
        origin.rollup.isNotable shouldBe true
    }

    @Test
    fun `BookmarkRollup divergenceText renders only the directions that are actually positive`() {
        BookmarkRollup().divergenceText() shouldBe ""
        BookmarkRollup(aheadCount = 2).divergenceText() shouldBe "↑2"
        BookmarkRollup(behindCount = 3).divergenceText() shouldBe "↓3"
        BookmarkRollup(aheadCount = 2, behindCount = 3).divergenceText() shouldBe "↑2↓3"
    }

    @Test
    fun `a category with only clean tracked bookmarks has an empty, non-notable rollup`() {
        val references = mapOf(repo to RepositoryReferences(bookmarks = listOf(item("main@origin"))))

        val origin = buildBookmarkTree(references, emptyMap(), emptyMap())
            .filterIsInstance<BookmarkNode.Category>().single { it.displayName == "origin" }

        origin.rollup shouldBe BookmarkRollup(leafCount = 1)
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

    // jj-idea-lig7 (GitHub #107): a visible head with no bookmark on it gets its own collapsible
    // "Unbookmarked heads" category, sitting right after the "@" row and before Local.

    @Test
    fun `no dangling heads means no Unbookmarked heads category`() {
        val tree = buildBookmarkTree(refs("main"), emptyMap(), emptyMap(), emptyMap())

        tree.none { it is BookmarkNode.Category && it.displayName == "Unbookmarked heads" } shouldBe true
    }

    @Test
    fun `dangling heads land in their own category, ordered by distance then id`() {
        val closeHead = DanglingHead(
            ChangeId("aaa", "aaa", null),
            ClosestBookmarks(listOf(BookmarkName("main")), distance = 1, distanceCapped = false)
        )
        val farHead = DanglingHead(
            ChangeId("bbb", "bbb", null),
            ClosestBookmarks(listOf(BookmarkName("main")), distance = 5, distanceCapped = false)
        )
        val danglingHeads = mapOf(repo to listOf(farHead, closeHead))

        val tree = buildBookmarkTree(refs("main"), emptyMap(), emptyMap(), danglingHeads)

        val categories = tree.filterIsInstance<BookmarkNode.Category>().map { it.displayName }
        categories shouldBe listOf("Unbookmarked heads", "Local")
        val unbookmarked =
            tree.filterIsInstance<BookmarkNode.Category>().first { it.displayName == "Unbookmarked heads" }
        val leaves = unbookmarked.children.filterIsInstance<BookmarkNode.DanglingHead>()
        leaves.map { it.id } shouldBe listOf(closeHead.id, farHead.id)
        leaves[0].displayName shouldBe "main +1 aaa"
    }

    @Test
    fun `the Unbookmarked heads category sits after the working copy row`() {
        val commitId = CommitId("abc123def456", "ab")
        val wcEntry = LogEntry(repo = repo, id = changeId, commitId = commitId, underlyingDescription = "")
        val closest = ClosestBookmarks(listOf(BookmarkName("main")), distance = 3, distanceCapped = false)
        val head = DanglingHead(ChangeId("aaa", "aaa", null), null)

        val tree = buildBookmarkTree(
            refs("main"),
            mapOf(repo to wcEntry),
            mapOf(repo to closest),
            mapOf(repo to listOf(head))
        )

        tree[0] shouldBe tree.first { it is BookmarkNode.WorkingCopy }
        tree[1] shouldBe tree.first { it is BookmarkNode.Category && it.displayName == "Unbookmarked heads" }
    }

    @Test
    fun `a dangling head with no ancestor bookmark at all uses the no-bookmark fallback label`() {
        val head = DanglingHead(ChangeId("aaa", "aaa", null), null)
        val tree = buildBookmarkTree(refs(), emptyMap(), emptyMap(), mapOf(repo to listOf(head)))

        val leaf =
            tree.filterIsInstance<BookmarkNode.Category>().single().children.single() as BookmarkNode.DanglingHead
        leaf.displayName shouldBe "(no bookmark) aaa"
        leaf.closest shouldBe null
    }

    @Test
    fun `the Unbookmarked heads category is flagged and carries no rollup`() {
        val head = DanglingHead(ChangeId("aaa", "aaa", null), null)
        val tree = buildBookmarkTree(refs(), emptyMap(), emptyMap(), mapOf(repo to listOf(head)))

        val unbookmarked = tree.filterIsInstance<BookmarkNode.Category>().single()
        unbookmarked.isDanglingHeadsGroup shouldBe true
        unbookmarked.rollup shouldBe BookmarkRollup.EMPTY
    }

    // jj-idea-uyu9: every Category/Prefix carries its own repo, and a Prefix additionally carries
    // the owning top-level Category's label plus its accumulated "/"-path, both needed by
    // bookmarkNodeTooltip without walking the tree.

    @Test
    fun `every Category and Prefix carries the repo its bookmarks belong to`() {
        val tree = buildBookmarkTree(refs("feature/a"), emptyMap(), emptyMap())

        val local = tree.single() as BookmarkNode.Category
        local.repo shouldBe repo
        val feature = local.children.single() as BookmarkNode.Prefix
        feature.repo shouldBe repo
    }

    @Test
    fun `a nested Prefix carries its owning category's label, not its own segment name`() {
        val references = mapOf(
            repo to RepositoryReferences(bookmarks = listOf(item("branches/foo/bar@origin")))
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val origin = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "origin" }
        val branches = origin.children.single() as BookmarkNode.Prefix
        val foo = branches.children.single() as BookmarkNode.Prefix

        branches.groupLabel shouldBe "origin"
        foo.groupLabel shouldBe "origin"
        branches.fullPath shouldBe "branches"
        foo.fullPath shouldBe "branches/foo"
    }

    // jj-idea-uyu9: WorkingCopy carries the same raw ingredients bookmarkWidgetText derives
    // displayName from, so a tooltip can render its own compact form without re-parsing text.

    @Test
    fun `working copy node carries the change id and raw bookmark-widget inputs`() {
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
        wc.id shouldBe changeId
        wc.onWorkingCopyNames shouldBe listOf("main")
        wc.closest shouldBe null
    }

    @Test
    fun `working copy node carries the closest-ancestor fallback when nothing sits on it`() {
        val commitId = CommitId("abc123def456", "ab")
        val wcEntry = LogEntry(repo = repo, id = changeId, commitId = commitId, underlyingDescription = "")
        val closest = ClosestBookmarks(listOf(Bookmark("main").name), distance = 3, distanceCapped = false)

        val tree = buildBookmarkTree(refs("main"), mapOf(repo to wcEntry), mapOf(repo to closest))

        val wc = tree.first() as BookmarkNode.WorkingCopy
        wc.onWorkingCopyNames shouldBe emptyList()
        wc.closest shouldBe closest
    }

    // jj-idea-uyu9 follow-up: a Local leaf carries every tracked remote-tracking row sharing its
    // name, for the tooltip's per-remote ahead/behind breakdown - display-only, reconstructed
    // in-memory from the already-loaded refs.bookmarks, no new jj call.

    @Test
    fun `a local bookmark tracked by two remotes carries both in remotes`() {
        val references = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(item("main"), item("main@origin"), item("main@github"))
            )
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val local = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "Local" }
        val main = local.children.filterIsInstance<BookmarkNode.Local>().single()

        main.remotes.map { it.remote }.toSet() shouldBe setOf("origin", "github")
    }

    @Test
    fun `a local bookmark's remotes excludes the git pseudo-remote and untracked rows`() {
        val references = mapOf(
            repo to RepositoryReferences(
                bookmarks = listOf(
                    item("main"),
                    item("main@git"),
                    item("main@origin", tracked = false)
                )
            )
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val local = tree.filterIsInstance<BookmarkNode.Category>().single { it.displayName == "Local" }
        val main = local.children.filterIsInstance<BookmarkNode.Local>().single()

        main.remotes shouldBe emptyList()
    }

    @Test
    fun `a local bookmark with no matching remote rows carries an empty remotes list`() {
        val tree = buildBookmarkTree(refs("main"), emptyMap(), emptyMap())

        val local = tree.single() as BookmarkNode.Category
        val main = local.children.single() as BookmarkNode.Local

        main.remotes shouldBe emptyList()
    }

    // jj-idea-uyu9 follow-up: BookmarkRollup.leafCount is a transitive count of bookmark/tag
    // leaves, for a folder tooltip's "N bookmarks"/"N tags" line.

    @Test
    fun `leafCount counts every leaf under a category, including nested prefixes`() {
        val tree = buildBookmarkTree(refs("feature/a", "feature/b", "zeta"), emptyMap(), emptyMap())

        val local = tree.single() as BookmarkNode.Category
        local.rollup.leafCount shouldBe 3
        val feature = local.children.filterIsInstance<BookmarkNode.Prefix>().single()
        feature.rollup.leafCount shouldBe 2
    }

    @Test
    fun `leafCount for a Tags category counts tag leaves`() {
        val references = mapOf(
            repo to RepositoryReferences(tags = listOf(TagItem(Tag("v1"), changeId), TagItem(Tag("v2"), changeId)))
        )

        val tree = buildBookmarkTree(references, emptyMap(), emptyMap())
        val tags = tree.single() as BookmarkNode.Category

        tags.rollup.leafCount shouldBe 2
    }

    @Test
    fun `top-level divergenceText matches BookmarkRollup's own`() {
        divergenceText(0, 0) shouldBe ""
        divergenceText(2, 0) shouldBe "↑2"
        divergenceText(0, 3) shouldBe "↓3"
        divergenceText(2, 3) shouldBe "↑2↓3"
    }
}
