package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.cli.LogTemplates.basicLogTemplate
import `in`.kkkev.jjidea.jj.cli.LogTemplates.commitGraphLogTemplate
import `in`.kkkev.jjidea.jj.cli.LogTemplates.fullLogTemplate
import `in`.kkkev.jjidea.jj.cli.LogTemplates.refsLogTemplate
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/**
 * Tests for LogTemplate integration and parsing
 */
class LogTemplateTest {

    companion object {
        const val Z = "\u0000"
    }

    @Test
    fun `basicLogTemplate generates correct spec string`() {
        val spec = basicLogTemplate.spec

        // Should contain all field specs joined with ++ and null byte separators
        spec shouldBe """change_id ++ "~" + change_id.shortest() ++ "\0" ++ commit_id ++ "\0" ++ description ++ "\0" ++ bookmarks.map(|b| b.name()).join(",") ++ "\0" ++ parents.map(|c| c.change_id() ++ "~" ++ c.change_id().shortest()).join(",") ++ "\0" ++ if(current_working_copy, "true", "false") ++ "\0" ++ if(conflict, "true", "false") ++ "\0" ++ if(empty, "true", "false") ++ "\0""""
    }

    @Test
    fun `basicLogTemplate has correct field count`() {
        // 8 single fields: changeId, commitId, description, bookmarks, parents, currentWorkingCopy, conflict, empty
        basicLogTemplate.count shouldBe 8
    }

    @Test
    fun `basicLogTemplate parses simple entry`() {
        val fields = listOf(
            "qpvuntsm~q",
            "abc123def456",
            "Add new feature",
            "",
            "",
            "false",
            "false",
            "false"
        )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.changeId shouldBe ChangeId("qpvuntsm", "q")
        entry.commitId shouldBe "abc123def456"
        entry.description.display shouldBe "Add new feature"
        entry.bookmarks.shouldBeEmpty()
        entry.parentIds.shouldBeEmpty()
        entry.isWorkingCopy shouldBe false
        entry.hasConflict shouldBe false
        entry.isEmpty shouldBe false
    }

    @Test
    fun `basicLogTemplate parses entry with bookmarks`() {
        val fields = listOf(
            "qpvuntsm~q",
            "abc123def456",
            "Feature work",
            "main,feature",
            "",
            "true",
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
        val fields = listOf(
            "qpvuntsm~q",
            "abc123def456",
            "Merge commit",
            "",
            "plkvukqt~p,rlvkpnrz~rl",
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
        val fields = listOf(
            "qpvuntsm~q",
            "abc123def456",
            "First line\nSecond line\nThird line",
            "",
            "",
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
            "qpvuntsm~q",
            "abc123def456",
            "",
            "",
            "",
            "false",
            "false",
            "true"
        )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.description.empty shouldBe true
        entry.isEmpty shouldBe true
    }

    @Test
    fun `basicLogTemplate parses undescribed commit`() {
        val fields = listOf(
            "qpvuntsm~q",
            "abc123def456",
            "",  // Empty description
            "",
            "",
            "false",
            "false",
            "false"  // Not empty
        )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.description.actual shouldBe ""
        entry.isEmpty shouldBe false
        entry.description.empty shouldBe true  // Empty description but not empty commit
    }

    @Test
    fun `basicLogTemplate parses conflict commit`() {
        val fields = listOf(
            "qpvuntsm~q",
            "abc123def456",
            "Conflicted change",
            "",
            "",
            "false",
            "true",
            "false"
        )

        val entry = basicLogTemplate.take(fields.iterator())

        entry.hasConflict shouldBe true
    }

    @Test
    fun `fullLogTemplate generates correct spec string`() {
        val spec = fullLogTemplate.spec

        // Should contain basic template fields plus author and committer
        spec shouldBe """change_id ++ "~" + change_id.shortest() ++ "\0" ++ commit_id ++ "\0" ++ description ++ "\0" ++ bookmarks.map(|b| b.name()).join(",") ++ "\0" ++ parents.map(|c| c.change_id() ++ "~" ++ c.change_id().shortest()).join(",") ++ "\0" ++ if(current_working_copy, "true", "false") ++ "\0" ++ if(conflict, "true", "false") ++ "\0" ++ if(empty, "true", "false") ++ "\0" ++ author.name() ++ "\0" ++ author.email() ++ "\0" ++ author.timestamp().utc().format("%s") ++ "\0" ++ committer.name() ++ "\0" ++ committer.email() ++ "\0" ++ committer.timestamp().utc().format("%s") ++ "\0""""
    }

    @Test
    fun `fullLogTemplate has correct field count`() {
        // 8 basic fields + 3 author fields + 3 committer fields = 14
        fullLogTemplate.count shouldBe 14
    }

    @Test
    fun `fullLogTemplate parses complete entry`() {
        val fields = listOf(
            "qpvuntsm~q",
            "abc123def456",
            "Add new feature",
            "",
            "",
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
        entry.commitId shouldBe "abc123def456"
        entry.description shouldBe "Add new feature"
        entry.author!!.name shouldBe "Test Author"
        entry.author!!.email shouldBe "author@example.com"
        entry.authorTimestamp shouldBe Instant.fromEpochSeconds(1234567890)
        entry.committer!!.name shouldBe "Test Committer"
        entry.committer!!.email shouldBe "committer@example.com"
        entry.committerTimestamp shouldBe Instant.fromEpochSeconds(1234567890)
    }

    @Test
    fun `fullLogTemplate parses entry with different author and committer`() {
        val fields = listOf(
            "qpvuntsm~q",
            "abc123def456",
            "Cherry-picked commit",
            "",
            "",
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
        entry.author!!.email shouldBe "original@example.com"
        entry.authorTimestamp shouldBe Instant.fromEpochSeconds(1000000000)
        entry.committer!!.name shouldBe "Cherry Picker"
        entry.committer!!.email shouldBe "picker@example.com"
        entry.committerTimestamp shouldBe Instant.fromEpochSeconds(2000000000)
    }

    @Test
    fun `refsLogTemplate generates correct spec string`() {
        val spec = refsLogTemplate.spec

        spec shouldBe """change_id ++ "~" + change_id.shortest() ++ "\0" ++ bookmarks.map(|b| b.name()).join(",") ++ "\0" ++ if(current_working_copy, "true", "false") ++ "\0""""
    }

    @Test
    fun `refsLogTemplate has correct field count`() {
        refsLogTemplate.count shouldBe 3
    }

    @Test
    fun `refsLogTemplate parses entry with only working copy marker`() {
        val fields = listOf(
            "qpvuntsm~q",
            "",
            "true"
        )

        val refs = refsLogTemplate.take(fields.iterator())

        refs shouldHaveSize 1
        refs[0].changeId shouldBe ChangeId("qpvuntsm", "q")
        refs[0].ref.toString() shouldBe "@"
    }

    @Test
    fun `refsLogTemplate parses entry with bookmarks`() {
        val fields = listOf(
            "qpvuntsm~q",
            "main,feature",
            "false"
        )

        val refs = refsLogTemplate.take(fields.iterator())

        refs shouldHaveSize 2
        refs[0].ref.toString() shouldBe "main"
        refs[1].ref.toString() shouldBe "feature"
    }

    @Test
    fun `refsLogTemplate parses entry with both working copy and bookmarks`() {
        val fields = listOf(
            "qpvuntsm~q",
            "main",
            "true"
        )

        val refs = refsLogTemplate.take(fields.iterator())

        refs shouldHaveSize 2
        refs[0].ref.toString() shouldBe "@"
        refs[1].ref.toString() shouldBe "main"
    }

    @Test
    fun `refsLogTemplate parses entry with no refs`() {
        val fields = listOf(
            "qpvuntsm~q",
            "",
            "false"
        )

        val refs = refsLogTemplate.take(fields.iterator())

        refs.shouldBeEmpty()
    }

    @Test
    fun `commitGraphLogTemplate generates correct spec string`() {
        val spec = commitGraphLogTemplate.spec

        spec shouldBe """change_id ++ "~" + change_id.shortest() ++ "\0" ++ parents.map(|c| c.change_id() ++ "~" ++ c.change_id().shortest()).join(", ") ++ "\0" ++ committer.timestamp().utc().format("%s") ++ "\0""""
    }

    @Test
    fun `commitGraphLogTemplate has correct field count`() {
        commitGraphLogTemplate.count shouldBe 3
    }

    @Test
    fun `commitGraphLogTemplate parses graph node`() {
        val fields = listOf(
            "qpvuntsm~q",
            "plkvukqt~p",
            "1234567890"
        )

        val node = commitGraphLogTemplate.take(fields.iterator())

        node.changeId shouldBe ChangeId("qpvuntsm", "q")
        node.parentIds shouldHaveSize 1
        node.parentIds[0] shouldBe ChangeId("plkvukqt", "p")
        node.timestamp shouldBe Instant.fromEpochSeconds(1234567890)
    }

    @Test
    fun `commitGraphLogTemplate parses merge node`() {
        val fields = listOf(
            "qpvuntsm~q",
            "plkvukqt~p,rlvkpnrz~rl",
            "1234567890"
        )

        val node = commitGraphLogTemplate.take(fields.iterator())

        node.parentIds shouldHaveSize 2
        node.parentIds[0] shouldBe ChangeId("plkvukqt", "p")
        node.parentIds[1] shouldBe ChangeId("rlvkpnrz", "rl")
    }

    @Test
    fun `commitGraphLogTemplate parses root node`() {
        val fields = listOf(
            "zxwvutsq~z",
            "",
            "1000000000"
        )

        val node = commitGraphLogTemplate.take(fields.iterator())

        node.changeId shouldBe ChangeId("zxwvutsq", "z")
        node.parentIds.shouldBeEmpty()
        node.timestamp shouldBe Instant.fromEpochSeconds(1000000000)
    }
}
