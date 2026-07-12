package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkName
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Description
import `in`.kkkev.jjidea.jj.Tag
import `in`.kkkev.jjidea.jj.mockRepo
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/**
 * Tests for LogTemplate integration and parsing
 */
class LogTemplateTest {
    private val cliLogService = CliLogService(mockRepo)
    private val basicLogTemplate = cliLogService.logTemplates.basicLogTemplate
    private val fullLogTemplate = cliLogService.logTemplates.fullLogTemplate

    @Test
    fun `basicLogTemplate parses simple entry`() {
        val fields = listOf(
            "qpvuntsm~q~3",
            "abc123def456~ab",
            "Add new feature",
            "", // bookmarks
            "", // tags
            "", // parents
            "false",
            "false",
            "false",
            "false",
            "false"
        )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.id shouldBe ChangeId("qpvuntsm", "q", 3)
        entry.commitId shouldBe CommitId("abc123def456", "ab")
        entry.description.display shouldBe "Add new feature"
        entry.bookmarks.shouldBeEmpty()
        entry.parentIds.shouldBeEmpty()
        entry.isWorkingCopy shouldBe false
        entry.hasConflict shouldBe false
        entry.isEmpty shouldBe false
        entry.immutable shouldBe false
        entry.hasPushedAncestor shouldBe false
    }

    @Test
    fun `basicLogTemplate parses entry with bookmarks`() {
        val fields = listOf(
            "qpvuntsm~q~",
            "abc123def456~ab",
            "Feature work",
            "main;true;false;0;0,feature;true;false;0;0", // bookmarks
            "", // tags
            "", // parents
            "true",
            "false",
            "false",
            "false",
            "false"
        )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.bookmarks shouldHaveSize 2
        entry.bookmarks.map { it.name.name } shouldBe listOf("main", "feature")
        entry.isWorkingCopy shouldBe true
    }

    @Test
    fun `basicLogTemplate parses entry with parents`() {
        val fields = listOf(
            "qpvuntsm~q~",
            "abc123def456~ab",
            "Merge commit",
            "", // bookmarks
            "", // tags
            "plkvukqt~p~|bcd123~bc,rlvkpnrz~rl~34|cde234~cde", // parents
            "false",
            "false",
            "false",
            "false",
            "false"
        )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.parentIds shouldHaveSize 2
        entry.parentIds[0] shouldBe ChangeId("plkvukqt", "p", null)
        entry.parentIds[1] shouldBe ChangeId("rlvkpnrz", "rl", 34)
    }

    @Test
    fun `basicLogTemplate parses entry with multi-line description`() {
        val fields = listOf(
            "qpvuntsm~q~",
            "abc123def456~ab",
            "First line\nSecond line\nThird line",
            "", // bookmarks
            "", // tags
            "", // parents
            "false",
            "false",
            "false",
            "false",
            "false"
        )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.description.display shouldBe "First line\nSecond line\nThird line"
    }

    @Test
    fun `basicLogTemplate parses empty commit`() {
        val fields = listOf(
            "qpvuntsm~q~",
            "abc123def456~ab",
            "",
            "", // bookmarks
            "", // tags
            "", // parents
            "false",
            "false",
            "true",
            "false",
            "false"
        )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.description.empty shouldBe true
        entry.isEmpty shouldBe true
    }

    @Test
    fun `basicLogTemplate parses undescribed commit`() {
        val fields = listOf(
            "qpvuntsm~q~",
            "abc123def456~abc",
            "", // Empty description
            "", // bookmarks
            "", // tags
            "", // parents
            "false",
            "false",
            "false", // Not empty
            "false",
            "false"
        )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.description.actual shouldBe ""
        entry.isEmpty shouldBe false
        entry.description.empty shouldBe true // Empty description but not empty commit
    }

    @Test
    fun `basicLogTemplate parses conflict commit`() {
        val fields = listOf(
            "qpvuntsm~q~",
            "abc123def456~ab",
            "Conflicted change",
            "", // bookmarks
            "", // tags
            "", // parents
            "false",
            "true",
            "false",
            "false",
            "false"
        )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.hasConflict shouldBe true
    }

    @Test
    fun `fullLogTemplate parses complete entry`() {
        val fields = listOf(
            "qpvuntsm~q~",
            "abc123def456~ab",
            "Add new feature",
            "", // bookmarks
            "", // tags
            "", // parents
            "false",
            "false",
            "false",
            "false",
            "false",
            "Test Author",
            "author@example.com",
            "1234567890",
            "Test Committer",
            "committer@example.com",
            "1234567890"
        )

        val entry = fullLogTemplate.take(fields.iterator())

        entry.id shouldBe ChangeId("qpvuntsm", "q")
        entry.commitId shouldBe CommitId("abc123def456", "ab")
        entry.description shouldBe Description("Add new feature")
        entry.author!!.name shouldBe "Test Author"
        entry.author.email shouldBe "author@example.com"
        entry.authorTimestamp shouldBe Instant.fromEpochSeconds(1234567890)
        entry.committer!!.name shouldBe "Test Committer"
        entry.committer.email shouldBe "committer@example.com"
        entry.committerTimestamp shouldBe Instant.fromEpochSeconds(1234567890)
    }

    @Test
    fun `fullLogTemplate parses entry with different author and committer`() {
        val fields = listOf(
            "qpvuntsm~q~",
            "abc123def456~ab",
            "Cherry-picked commit",
            "", // bookmarks
            "", // tags
            "", // parents
            "false",
            "false",
            "false",
            "false",
            "false",
            "Original Author",
            "original@example.com",
            "1000000000",
            "Cherry Picker",
            "picker@example.com",
            "2000000000"
        )

        val entry = fullLogTemplate.take(fields.iterator())

        entry.author!!.name shouldBe "Original Author"
        entry.author.email shouldBe "original@example.com"
        entry.authorTimestamp shouldBe Instant.fromEpochSeconds(1000000000)
        entry.committer!!.name shouldBe "Cherry Picker"
        entry.committer.email shouldBe "picker@example.com"
        entry.committerTimestamp shouldBe Instant.fromEpochSeconds(2000000000)
    }

    @Test
    fun `basicLogTemplate parses entry with tags`() {
        val fields = listOf(
            "qpvuntsm~q~",
            "abc123def456~ab",
            "Tagged release",
            "", // bookmarks
            "v1.0,v1.0-rc1", // tags
            "", // parents
            "false",
            "false",
            "false",
            "false",
            "false"
        )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.tags shouldBe listOf(Tag("v1.0"), Tag("v1.0-rc1"))
    }

    @Test
    fun `basicLogTemplate parses entry with empty tags`() {
        val fields = listOf(
            "qpvuntsm~q~",
            "abc123def456~ab",
            "No tags",
            "", // bookmarks
            "", // tags (empty)
            "", // parents
            "false",
            "false",
            "false",
            "false",
            "false"
        )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.tags.shouldBeEmpty()
    }

    @Test
    fun `basicLogTemplate parses bookmark conflict and ahead-behind counts`() {
        val fields = listOf(
            "qpvuntsm~q~",
            "abc123def456~ab",
            "Feature work",
            "main;true;false;0;0,feature@origin;true;true;3;1", // bookmarks
            "", // tags
            "", // parents
            "false",
            "false",
            "false",
            "false",
            "false"
        )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.bookmarks shouldHaveSize 2
        val main = entry.bookmarks.first { it.name.name == "main" }
        main.conflict shouldBe false
        main.aheadCount shouldBe 0
        main.behindCount shouldBe 0
        val remote = entry.bookmarks.first { it.name.name == "feature@origin" }
        remote.conflict shouldBe true
        remote.aheadCount shouldBe 3
        remote.behindCount shouldBe 1
        remote.isDiverged shouldBe true
    }

    private val tagListTemplate = cliLogService.logTemplates.tagListTemplate

    @Test
    fun `tagListTemplate parses present mutable tag`() {
        val fields = listOf("true", "v1.0", "qpvuntsm~q~", "false")
        val item = tagListTemplate.take(fields.iterator())

        item!!.tag shouldBe Tag("v1.0")
        item.id shouldBe ChangeId("qpvuntsm", "q", null)
        item.immutable shouldBe false
    }

    @Test
    fun `tagListTemplate parses present immutable tag`() {
        val fields = listOf("true", "v1.0", "qpvuntsm~q~", "true")
        val item = tagListTemplate.take(fields.iterator())

        item!!.tag shouldBe Tag("v1.0")
        item.id shouldBe ChangeId("qpvuntsm", "q", null)
        item.immutable shouldBe true
    }

    @Test
    fun `tagListTemplate returns null for empty name`() {
        val fields = listOf("false", "", "", "false")
        val item = tagListTemplate.take(fields.iterator())

        item shouldBe null
    }

    private val bookmarkListTemplate = cliLogService.logTemplates.bookmarkListTemplate

    @Test
    fun `bookmarkListTemplate parses present bookmark`() {
        val fields = listOf("true", "main", "false", "qpvuntsm~q~", "false")
        val item = bookmarkListTemplate.take(fields.iterator())

        item!!.bookmark shouldBe Bookmark("main", conflict = false)
        item.id shouldBe ChangeId("qpvuntsm", "q", null)
        item.immutable shouldBe false
    }

    @Test
    fun `bookmarkListTemplate parses present immutable bookmark`() {
        val fields = listOf("true", "main", "false", "qpvuntsm~q~", "true")
        val item = bookmarkListTemplate.take(fields.iterator())

        item!!.bookmark shouldBe Bookmark("main", conflict = false)
        item.id shouldBe ChangeId("qpvuntsm", "q", null)
        item.immutable shouldBe true
    }

    @Test
    fun `bookmarkListTemplate parses pending-delete bookmark`() {
        val fields = listOf("false", "feature", "false", "", "false")
        val item = bookmarkListTemplate.take(fields.iterator())

        item!!.bookmark.name shouldBe BookmarkName("feature")
        item.bookmark.deleted shouldBe true
        item.id shouldBe null
        item.immutable shouldBe false
    }

    @Test
    fun `bookmarkListTemplate parses conflicted bookmark`() {
        val fields = listOf("true", "main", "true", "qpvuntsm~q~", "false")
        val item = bookmarkListTemplate.take(fields.iterator())

        item!!.bookmark.conflict shouldBe true
        item.id shouldBe ChangeId("qpvuntsm", "q", null)
    }

    @Test
    fun `bookmarkListTemplate parses remote-only untracked bookmark`() {
        // name arrives pre-formatted as "name@remote" by nameWithRemote(), as jj's `--all-remotes`
        // output does for a bookmark with no local counterpart.
        val fields = listOf("true", "feature@origin", "false", "qpvuntsm~q~", "false")
        val item = bookmarkListTemplate.take(fields.iterator())

        item!!.bookmark.name shouldBe BookmarkName("feature@origin")
        item.bookmark.isRemote shouldBe true
        item.bookmark.deleted shouldBe false
        item.id shouldBe ChangeId("qpvuntsm", "q", null)
    }
}
