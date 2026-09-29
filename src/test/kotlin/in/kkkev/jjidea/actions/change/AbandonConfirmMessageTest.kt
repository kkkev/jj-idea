package `in`.kkkev.jjidea.actions.change

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Regression test for GitHub #129 / jj-idea-940s: abandoning a non-empty change or one with a
 * description already prompts for confirmation. Abandoning an *empty*, undescribed change that
 * carries a local bookmark must also prompt, since `jj abandon` deletes local bookmarks pointing
 * at the abandoned revision. Remote-tracking and already-deleted bookmarks are unaffected by
 * `jj abandon`, so they must not trigger the prompt on their own.
 */
class AbandonConfirmMessageTest {
    private val repo = mockk<JujutsuRepository>()

    private fun entry(
        description: String = "",
        isEmpty: Boolean = true,
        bookmarks: List<Bookmark> = emptyList()
    ) = LogEntry(
        repo = repo,
        id = ChangeId("ab12" + "a".repeat(60), "ab12"),
        commitId = CommitId("0000000000000000000000000000000000000000"),
        underlyingDescription = description,
        bookmarks = bookmarks,
        parentIds = emptyList(),
        isWorkingCopy = false,
        hasConflict = false,
        isEmpty = isEmpty,
        authorTimestamp = null,
        committerTimestamp = null,
        author = null,
        committer = null,
        immutable = false
    )

    @Test
    fun `empty, undescribed, unbookmarked change needs no confirmation`() {
        abandonConfirmMessage(entry()).shouldBeNull()
    }

    @Test
    fun `empty, undescribed change with a local bookmark is confirmed and names it`() {
        val message = abandonConfirmMessage(entry(bookmarks = listOf(Bookmark("feature"))))

        message shouldNotBe null
        message!! shouldContain "feature"
        message shouldContain "deleted"
    }

    @Test
    fun `a remote-tracking bookmark alone needs no confirmation`() {
        abandonConfirmMessage(entry(bookmarks = listOf(Bookmark("main@origin")))).shouldBeNull()
    }

    @Test
    fun `a deleted local bookmark alone needs no confirmation`() {
        abandonConfirmMessage(entry(bookmarks = listOf(Bookmark("feature", deleted = true)))).shouldBeNull()
    }

    @Test
    fun `files only is confirmed`() {
        val message = abandonConfirmMessage(entry(isEmpty = false))

        message shouldNotBe null
        message!! shouldContain "file modifications"
    }

    @Test
    fun `description only is confirmed`() {
        val message = abandonConfirmMessage(entry(description = "Some work"))

        message shouldNotBe null
        message!! shouldContain "a description"
    }

    @Test
    fun `files, description, and multiple bookmarks are all listed with plural wording`() {
        val message = abandonConfirmMessage(
            entry(
                description = "Some work",
                isEmpty = false,
                bookmarks = listOf(Bookmark("feature"), Bookmark("other"))
            )
        )

        message shouldNotBe null
        message!! shouldContain "file modifications"
        message shouldContain "a description"
        message shouldContain "bookmarks feature, other"
    }
}
