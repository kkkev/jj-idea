package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.openapi.vcs.FilePath
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogCache
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.LogService
import `in`.kkkev.jjidea.jj.Revset
import `in`.kkkev.jjidea.jj.commandResult
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Exercises the revset-round-trip that [BookmarkClassifier] alone can't cover: building the query from
 * [in.kkkev.jjidea.jj.ChangeId]s, sending it through [CommandExecutor.log], and parsing the result back into a
 * forward/backward classification. This is where jj-idea-tvch's bug lived — an unqualified divergent id anywhere
 * in the revset makes jj fail the whole query, silently defaulting every candidate to backward/sideways.
 *
 * The `log` call is intercepted with a plain `object : CommandExecutor by mockk(relaxed = true)` override (the
 * pattern used in [in.kkkev.jjidea.integration.LogServiceIntegrationTest]) rather than a mockk `every { }`/`match
 * { }` on the call itself: mockk's matcher machinery throws `IllegalStateException: null packRef` for [Revset], a
 * sealed interface backed by an inline value class ([in.kkkev.jjidea.jj.Expression]).
 */
class MoveBookmarkDirectionTest {
    private fun changeId(full: String, offset: Int? = null) = ChangeId(full, full.take(4), offset)

    private fun fakeExecutor(stdout: String): Pair<CommandExecutor, () -> Revset> {
        var lastRevset: Revset? = null
        val executor = object : CommandExecutor by mockk(relaxed = true) {
            override fun log(
                revset: Revset,
                template: String?,
                filePaths: List<FilePath>,
                limit: Int?,
                quiet: Boolean
            ): CommandExecutor.CommandResult {
                lastRevset = revset
                return commandResult(0, stdout, "")
            }
        }
        return executor to { lastRevset ?: error("log() was never called") }
    }

    @Test
    fun `MoveBookmarkDialog classifies an ancestor bookmark as FORWARD`() {
        val target = changeId("targetfull")
        val ancestorBookmark = BookmarkItem(Bookmark("main"), changeId("ancestorfull"))

        val (executor, revsetUsed) = fakeExecutor("ancestorfull\n")

        val logService = mockk<LogService>()
        every { logService.getBookmarks() } returns Result.success(listOf(ancestorBookmark))

        val repo = mockk<JujutsuRepository>()
        every { repo.commandExecutor } returns executor
        every { repo.logService } returns logService

        val result = MoveBookmarkDialog.loadData(repo, target)

        result.single().direction shouldBe MoveDirection.FORWARD
        revsetUsed().toString() shouldBe "(ancestorfull) & ::targetfull"
    }

    @Test
    fun `MoveBookmarkDialog resolves cleanly when a divergent bookmark is present`() {
        // Reproduces the real-repo bug: an unqualified divergent id makes `jj log` fail entirely. The revset
        // built here must be offset-qualified so it succeeds instead.
        val target = changeId("targetfull")
        val forwardBookmark = BookmarkItem(Bookmark("main"), changeId("ancestorfull"))
        val divergentBookmark = BookmarkItem(Bookmark("dev"), changeId("shared", offset = 3))

        val (executor, revsetUsed) = fakeExecutor("ancestorfull\nshared/3\n")

        val logService = mockk<LogService>()
        every { logService.getBookmarks() } returns Result.success(listOf(forwardBookmark, divergentBookmark))

        val repo = mockk<JujutsuRepository>()
        every { repo.commandExecutor } returns executor
        every { repo.logService } returns logService

        val result = MoveBookmarkDialog.loadData(repo, target)

        result.find { it.item.bookmark.name.name == "main" }!!.direction shouldBe MoveDirection.FORWARD
        result.find { it.item.bookmark.name.name == "dev" }!!.direction shouldBe MoveDirection.FORWARD

        val revsetText = revsetUsed().toString()
        val candidateIds = revsetText.substringAfter("(").substringBefore(")").split(" | ").toSet()
        candidateIds shouldBe setOf("ancestorfull", "shared/3")
        revsetText.substringAfter(") & ") shouldBe "::targetfull"
    }

    @Test
    fun `MoveBookmarkToChangeDialog classifies a descendant entry as FORWARD without candidate ids in the revset`() {
        val currentId = changeId("currentfull")
        val bookmark = Bookmark("main")
        val bookmarkItem = BookmarkItem(bookmark, currentId)

        val repo = mockk<JujutsuRepository>()

        fun entry(full: String) = LogEntry(repo, changeId(full), CommitId(full), "desc")
        val descendant = entry("descfull")
        val ancestor = entry("ancestorfull")

        val (executor, revsetUsed) = fakeExecutor("descfull\n")

        val logService = mockk<LogService>()
        every { logService.getBookmarks() } returns Result.success(listOf(bookmarkItem))

        val logCache = mockk<LogCache>()
        every { logCache.all } returns listOf(descendant, ancestor)

        every { repo.commandExecutor } returns executor
        every { repo.logService } returns logService
        every { repo.logCache } returns logCache

        val result = MoveBookmarkToChangeDialog.loadData(repo, bookmark)

        result.find { it.first.id.full == "descfull" }!!.second shouldBe MoveDirection.FORWARD
        result.find { it.first.id.full == "ancestorfull" }!!.second shouldBe MoveDirection.BACKWARD_OR_SIDEWAYS
        // Candidate ids must never appear on the revset - only the bookmark's current position does.
        revsetUsed().toString() shouldBe "currentfull::"
    }
}
