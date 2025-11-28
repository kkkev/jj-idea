package `in`.kkkev.jjidea.ui

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Tests for parsing jj log output into structured log entries
 */
class JujutsuLogParserTest {

    @Test
    fun `parse simple log entry`() {
        // jj log output with template showing: change_id, commit_id, description, bookmarks, etc.
        val logLine = "qpvuntsm|abc123def456|Add new feature||false|false|false"

        val entry = JujutsuLogParser.parseLogLine(logLine, shortPrefixLength = 2)

        entry.changeId shouldBe "qpvuntsm"
        entry.commitId shouldBe "abc123def456"
        entry.description shouldBe "Add new feature"
        entry.bookmarks shouldHaveSize 0
        entry.shortChangeIdPrefix shouldBe "qp"
    }

    @Test
    fun `parse log entry with bookmarks`() {
        val logLine = "qpvuntsm|abc123def456|Add new feature|main,feature-branch|false|false|false"

        val entry = JujutsuLogParser.parseLogLine(logLine, shortPrefixLength = 2)

        entry.bookmarks shouldHaveSize 2
        entry.bookmarks shouldBe listOf("main", "feature-branch")
    }

    @Test
    fun `parse working copy entry`() {
        val logLine = "qpvuntsm|abc123def456|Work in progress||true|false|false"

        val entry = JujutsuLogParser.parseLogLine(logLine, shortPrefixLength = 2)

        entry.isWorkingCopy shouldBe true
    }

    @Test
    fun `parse entry with conflict`() {
        val logLine = "qpvuntsm|abc123def456|Conflicted change||false|true|false"

        val entry = JujutsuLogParser.parseLogLine(logLine, shortPrefixLength = 2)

        entry.hasConflict shouldBe true
    }

    @Test
    fun `parse empty commit`() {
        val logLine = "qpvuntsm|abc123def456|||false|false|true"

        val entry = JujutsuLogParser.parseLogLine(logLine, shortPrefixLength = 2)

        entry.isEmpty shouldBe true
        entry.description shouldBe ""
    }

    @Test
    fun `parse multiple log entries`() {
        val logOutput = """
            qpvuntsm|abc123|Add feature|main|true|false|false
            rlvkpnrz|def456|Fix bug||false|false|false
            zxwvutsq|ghi789|Initial commit||false|false|false
        """.trimIndent()

        val entries = JujutsuLogParser.parseLog(logOutput)

        entries shouldHaveSize 3
        entries[0].changeId shouldBe "qpvuntsm"
        entries[1].changeId shouldBe "rlvkpnrz"
        entries[2].changeId shouldBe "zxwvutsq"
    }

    @Test
    fun `determine short prefix length from jj output`() {
        // In real jj output, the short prefix is determined by uniqueness
        // For testing, we'll assume we get this from jj log with --template
        val changeId = "qpvuntsm"

        val shortPrefix = JujutsuLogParser.getShortPrefix(changeId, prefixLength = 2)

        shortPrefix shouldBe "qp"
    }
}
