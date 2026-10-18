package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FilePath
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * jj-idea-wiz0 (GitHub #130): a ref left at a hidden commit must resolve to that commit, never to the visible commit
 * sharing its change id.
 *
 * Complexity claim for [loadHiddenTargets]: zero `jj log` calls when no target is hidden, regardless of how many refs
 * there are, and exactly one call (not one per target) when some are, with at most [HIDDEN_TARGET_LIMIT] ids in it.
 */
class HiddenRefTargetsTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private val changeId = ChangeId("kkkk", "k", null)
    private val a1 = CommitId("a1".repeat(20), "a1")
    private val a2 = CommitId("a2".repeat(20), "a2")

    private fun entry(commitId: CommitId, hidden: Boolean = false) = LogEntry(
        repo = repo,
        id = changeId,
        commitId = commitId,
        underlyingDescription = "desc",
        isHidden = hidden
    )

    private fun remoteAtHiddenA1() = BookmarkItem(
        Bookmark("cl/X@origin"),
        listOf(changeId),
        listOf(false),
        listOf(a1),
        listOf(true)
    )

    private fun localAtA2() = BookmarkItem(Bookmark("cl/X"), listOf(changeId), listOf(false), listOf(a2), listOf(false))

    @Test
    fun `a hidden target resolves from the hidden entries, not the visible commit with its change id`() {
        val visible = entry(a2)
        val hidden = entry(a1, hidden = true)

        val resolved = remoteAtHiddenA1().resolveEntry(repo, { visible }, mapOf(a1 to hidden))

        resolved shouldBe hidden
    }

    @Test
    fun `a hidden target with no loaded hidden entry resolves to nothing rather than the visible commit`() {
        remoteAtHiddenA1().resolveEntry(repo, { entry(a2) }, emptyMap()) shouldBe null
    }

    @Test
    fun `a visible target resolves through the lookup only when it is that commit`() {
        val visible = entry(a2)

        localAtA2().resolveEntry(repo, { visible }, emptyMap()) shouldBe visible
        // The log has been rewritten since the references loaded: a1 is now what the lookup finds.
        localAtA2().resolveEntry(repo, { entry(a1) }, emptyMap()) shouldBe null
    }

    @Test
    fun `a ref without commit ids falls back to the change id lookup`() {
        val visible = entry(a2)

        BookmarkItem(Bookmark("main"), changeId).resolveEntry(repo, { visible }, emptyMap()) shouldBe visible
    }

    @Test
    fun `exact divergence revsets name full commit ids when known and change ids otherwise`() {
        remoteAtHiddenA1().targetRevsetArgs() shouldContainExactly listOf(a1.full)
        BookmarkItem(Bookmark("main"), changeId).targetRevsetArgs() shouldContainExactly listOf(changeId.toString())
    }

    private class CountingLogService(private val result: List<LogEntry>) : LogService by mockk(relaxed = true) {
        val revsets = mutableListOf<Revset>()

        override fun getLogBasic(revset: Revset, filePaths: List<FilePath>, limit: Int?): Result<List<LogEntry>> {
            revsets += revset
            return Result.success(result)
        }
    }

    @Test
    fun `no jj log call when no target is hidden, however many refs there are`() {
        val service = CountingLogService(emptyList())
        val items = (0 until 5_000).map { localAtA2().copy(bookmark = Bookmark("b$it")) }

        service.loadHiddenTargets(items, emptyList()) shouldBe emptyMap()

        service.revsets.size shouldBe 0
    }

    @Test
    fun `one batched jj log call for any number of hidden targets`() {
        val hiddenIds = (0 until 3).map { CommitId("b$it".repeat(20), "b$it") }
        val items = hiddenIds.map {
            BookmarkItem(Bookmark("r$it@origin"), listOf(changeId), listOf(false), listOf(it), listOf(true))
        } + remoteAtHiddenA1() + localAtA2()
        val service = CountingLogService(hiddenIds.map { entry(it, hidden = true) } + entry(a1, hidden = true))

        val loaded = service.loadHiddenTargets(items, emptyList())

        service.revsets.size shouldBe 1
        loaded.keys.toList() shouldContainExactly hiddenIds + a1
        (service.revsets.single() as Expression).toString().split(" | ").size shouldBe 4
    }
}
