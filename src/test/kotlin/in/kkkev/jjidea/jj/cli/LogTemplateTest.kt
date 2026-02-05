package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.Description
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
    private val refsLogTemplate = cliLogService.logTemplates.refsLogTemplate
    private val commitGraphLogTemplate = cliLogService.logTemplates.commitGraphLogTemplate

    @Test
    fun `basicLogTemplate parses simple entry`() {
        val fields =
            listOf(
                "qpvuntsm~q",
                "abc123def456~ab",
                "Add new feature",
                "",
                "",
                "false",
                "false",
                "false",
                "false"
            )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.changeId shouldBe ChangeId("qpvuntsm", "q")
        entry.commitId shouldBe CommitId("abc123def456", "ab")
        entry.description.display shouldBe "Add new feature"
        entry.bookmarks.shouldBeEmpty()
        entry.parentIds.shouldBeEmpty()
        entry.isWorkingCopy shouldBe false
        entry.hasConflict shouldBe false
        entry.isEmpty shouldBe false
        entry.immutable shouldBe false
    }

    @Test
    fun `basicLogTemplate parses entry with bookmarks`() {
        val fields =
            listOf(
                "qpvuntsm~q",
                "abc123def456~ab",
                "Feature work",
                "main,feature",
                "",
                "true",
                "false",
                "false",
                "false"
            )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.bookmarks shouldHaveSize 2
        entry.bookmarks.map { it.name } shouldBe listOf("main", "feature")
        entry.isWorkingCopy shouldBe true
    }

    @Test
    fun `basicLogTemplate parses entry with parents`() {
        val fields =
            listOf(
                "qpvuntsm~q",
                "abc123def456~ab",
                "Merge commit",
                "",
                "plkvukqt~p|bcd123~bc,rlvkpnrz~rl|cde234~cde",
                "false",
                "false",
                "false",
                "false"
            )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.parentIds shouldHaveSize 2
        entry.parentIds[0] shouldBe ChangeId("plkvukqt", "p")
        entry.parentIds[1] shouldBe ChangeId("rlvkpnrz", "rl")
    }

    @Test
    fun `basicLogTemplate parses entry with multi-line description`() {
        val fields =
            listOf(
                "qpvuntsm~q",
                "abc123def456~ab",
                "First line\nSecond line\nThird line",
                "",
                "",
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
        val fields =
            listOf(
                "qpvuntsm~q",
                "abc123def456~ab",
                "",
                "",
                "",
                "false",
                "false",
                "true",
                "false"
            )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.description.empty shouldBe true
        entry.isEmpty shouldBe true
    }

    @Test
    fun `basicLogTemplate parses undescribed commit`() {
        val fields =
            listOf(
                "qpvuntsm~q",
                "abc123def456~abc",
                "", // Empty description
                "",
                "",
                "false",
                "false",
                "false", // Not empty
                "false"
            )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.description.actual shouldBe ""
        entry.isEmpty shouldBe false
        entry.description.empty shouldBe true // Empty description but not empty commit
    }

    @Test
    fun `basicLogTemplate parses conflict commit`() {
        val fields =
            listOf(
                "qpvuntsm~q",
                "abc123def456~ab",
                "Conflicted change",
                "",
                "",
                "false",
                "true",
                "false",
                "false"
            )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.hasConflict shouldBe true
    }

    @Test
    fun `fullLogTemplate parses complete entry`() {
        val fields =
            listOf(
                "qpvuntsm~q",
                "abc123def456~ab",
                "Add new feature",
                "",
                "",
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

        entry.changeId shouldBe ChangeId("qpvuntsm", "q")
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
        val fields =
            listOf(
                "qpvuntsm~q",
                "abc123def456~ab",
                "Cherry-picked commit",
                "",
                "",
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
    fun `refsLogTemplate parses entry with only working copy marker`() {
        val fields = listOf("123abc~12", "", "true")

        val refs = refsLogTemplate.take(fields.iterator())

        refs shouldHaveSize 1
        refs[0].commitId shouldBe CommitId("123abc", "12")
        refs[0].ref.toString() shouldBe "@"
    }

    @Test
    fun `refsLogTemplate parses entry with bookmarks`() {
        val fields = listOf("qpvuntsm~q", "main,feature", "false")

        val refs = refsLogTemplate.take(fields.iterator())

        refs shouldHaveSize 2
        refs[0].ref.toString() shouldBe "main"
        refs[1].ref.toString() shouldBe "feature"
    }

    @Test
    fun `refsLogTemplate parses entry with both working copy and bookmarks`() {
        val fields = listOf("qpvuntsm~q", "main", "true")

        val refs = refsLogTemplate.take(fields.iterator())

        refs shouldHaveSize 2
        refs[0].ref.toString() shouldBe "@"
        refs[1].ref.toString() shouldBe "main"
    }

    @Test
    fun `refsLogTemplate parses entry with no refs`() {
        val fields = listOf("qpvuntsm~q", "", "false")

        val refs = refsLogTemplate.take(fields.iterator())

        refs.shouldBeEmpty()
    }

    @Test
    fun `commitGraphLogTemplate parses graph node`() {
        val fields = listOf("123abc~12", "qvuntsm~qv|234bcd~23", "1234567890")

        val node = commitGraphLogTemplate.take(fields.iterator())

        node.id.toString() shouldBe "123abc"
        node.parents shouldHaveSize 1
        node.parents[0].toString() shouldBe "234bcd"
        node.timestamp shouldBe 1234567890000L
    }

    @Test
    fun `commitGraphLogTemplate parses merge node`() {
        val fields = listOf("123abc~12", "qvuntsm~qv|234bcd~23,ruvvqw~ru|135abc~135", "1234567890")

        val node = commitGraphLogTemplate.take(fields.iterator())

        node.parents shouldHaveSize 2
        node.parents[0].toString() shouldBe "234bcd"
        node.parents[1].toString() shouldBe "135abc"
    }

    @Test
    fun `commitGraphLogTemplate parses root node`() {
        val fields = listOf("123456~123", "", "1000000000")

        val node = commitGraphLogTemplate.take(fields.iterator())

        node.id.toString() shouldBe "123456"
        node.parents.shouldBeEmpty()
        node.timestamp shouldBe 1000000000000L
    }
}
