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
        // jj log output with null byte separator and trailing null byte
        val logLine = "qpvuntsm${Z}q${Z}abc123def456${Z}Add new feature${Z}${Z}false${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.changeId shouldBe "qpvuntsm"
        entry.commitId shouldBe "abc123def456"
        entry.description shouldBe "Add new feature"
        entry.bookmarks shouldHaveSize 0
        entry.shortChangeIdPrefix shouldBe "q"
    }

    @Test
    fun `parse log entry with bookmarks`() {
        val logLine = "qpvuntsm${Z}q${Z}abc123def456${Z}Add new feature${Z}main,feature-branch${Z}false${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.bookmarks shouldHaveSize 2
        entry.bookmarks shouldBe listOf("main", "feature-branch")
    }

    @Test
    fun `parse working copy entry`() {
        val logLine = "qpvuntsm${Z}q${Z}abc123def456${Z}Work in progress${Z}${Z}true${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.isWorkingCopy shouldBe true
    }

    @Test
    fun `parse entry with conflict`() {
        val logLine = "qpvuntsm${Z}q${Z}abc123def456${Z}Conflicted change${Z}${Z}false${Z}true${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.hasConflict shouldBe true
    }

    @Test
    fun `parse empty commit`() {
        val logLine = "qpvuntsm${Z}q${Z}abc123def456${Z}${Z}${Z}false${Z}false${Z}true$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.isEmpty shouldBe true
        entry.description shouldBe ""
    }

    @Test
    fun `parse multiple log entries`() {
        // Entries concatenated with trailing \0 as separator (no newlines between entries)
        val entry1 = "qpvuntsm${Z}q${Z}abc123${Z}Add feature${Z}main${Z}true${Z}false${Z}false$Z"
        val entry2 = "rlvkpnrz${Z}rl${Z}def456${Z}Fix bug${Z}${Z}false${Z}false${Z}false$Z"
        val entry3 = "zxwvutsq${Z}z${Z}ghi789${Z}Initial commit${Z}${Z}false${Z}false${Z}false$Z"
        val logOutput = entry1 + entry2 + entry3

        val entries = JujutsuLogParser.parseLog(logOutput)

        entries shouldHaveSize 3
        entries[0].changeId shouldBe "qpvuntsm"
        entries[1].changeId shouldBe "rlvkpnrz"
        entries[2].changeId shouldBe "zxwvutsq"
    }

    @Test
    fun `parse log entry with multi-line description`() {
        // Description with embedded newline
        val logLine = "qpvuntsm${Z}qp${Z}abc123def456${Z}First line\nSecond line${Z}${Z}false${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.changeId shouldBe "qpvuntsm"
        entry.commitId shouldBe "abc123def456"
        entry.description shouldBe "First line\nSecond line"
        entry.bookmarks shouldHaveSize 0
    }

    @Test
    fun `parse multiple entries when one has multi-line description`() {
        // Entries concatenated; description contains actual newlines
        val entry1 = "qpvuntsm${Z}q${Z}abc123${Z}First line\nSecond line of same commit${Z}main${Z}true${Z}false${Z}false$Z"
        val entry2 = "rlvkpnrz${Z}rlv${Z}def456${Z}Single line description${Z}${Z}false${Z}false${Z}false$Z"
        val logOutput = entry1 + entry2

        val entries = JujutsuLogParser.parseLog(logOutput)

        entries shouldHaveSize 2
        entries[0].description shouldBe "First line\nSecond line of same commit"
        entries[1].description shouldBe "Single line description"
    }

    @Test
    fun `parse entry with multi-line description spanning three lines`() {
        val entry1 = "qpvuntsm${Z}q${Z}abc123${Z}Line 1\nLine 2\nLine 3${Z}main${Z}true${Z}false${Z}false$Z"
        val entry2 = "rlvkpnrz${Z}rl${Z}def456${Z}Another commit${Z}${Z}false${Z}false${Z}false$Z"
        val logOutput = entry1 + entry2

        val entries = JujutsuLogParser.parseLog(logOutput)

        entries shouldHaveSize 2
        entries[0].description shouldBe "Line 1\nLine 2\nLine 3"
        entries[0].changeId shouldBe "qpvuntsm"
        entries[1].description shouldBe "Another commit"
        entries[1].changeId shouldBe "rlvkpnrz"
    }

    @Test
    fun `parse entry with empty lines in multi-line description`() {
        // Description with blank line (actual double newline in description field)
        val entry1 = "qpvuntsm${Z}q${Z}abc123${Z}First paragraph\n\nSecond paragraph${Z}main${Z}true${Z}false${Z}false$Z"
        val logOutput = entry1

        val entries = JujutsuLogParser.parseLog(logOutput)

        entries shouldHaveSize 1
        entries[0].description shouldBe "First paragraph\n\nSecond paragraph"
    }

    @Test
    fun `parse entry with pipe character in description`() {
        // Description with | character should not break parsing when using null byte separator
        val logLine = "qpvuntsm${Z}q${Z}abc123def456${Z}Use grep | sort | uniq${Z}${Z}false${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.changeId shouldBe "qpvuntsm"
        entry.commitId shouldBe "abc123def456"
        entry.description shouldBe "Use grep | sort | uniq"
    }

    @Test
    fun `parse entries with descriptions containing pipe characters and newlines`() {
        // Test that both pipe characters and newlines work correctly with null byte separator
        val entry1 = "abc123${Z}a${Z}commit1${Z}Use command: ls | grep foo${Z}${Z}false${Z}false${Z}false$Z"
        val entry2 = "def456${Z}d${Z}commit2${Z}Multi-line\nWith pipes |\nAnd more text${Z}main${Z}true${Z}false${Z}false$Z"

        val logOutput = entry1 + entry2

        val entries = JujutsuLogParser.parseLog(logOutput)

        entries shouldHaveSize 2
        entries[0].description shouldBe "Use command: ls | grep foo"
        entries[1].description shouldBe "Multi-line\nWith pipes |\nAnd more text"
        entries[1].bookmarks shouldBe listOf("main")
    }

    @Test
    fun `parse entry with empty description and empty bookmarks`() {
        // This was the bug: when both description and bookmarks are empty, jj's separate() skips them
        // Using ++ concatenation preserves empty fields
        val logLine = "wnxouzzv${Z}w${Z}46da5a6ad44feb2fa45fc03309fab4c1f25ff05d${Z}${Z}${Z}true${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.changeId shouldBe "wnxouzzv"
        entry.shortChangeIdPrefix shouldBe "w"
        entry.commitId shouldBe "46da5a6ad44feb2fa45fc03309fab4c1f25ff05d"
        entry.description shouldBe ""
        entry.bookmarks shouldHaveSize 0
        entry.isWorkingCopy shouldBe true
        entry.hasConflict shouldBe false
        entry.isEmpty shouldBe false
    }

    @Test
    fun `parse entries with trailing null byte as entry separator`() {
        // Trailing \0 separates entries even when descriptions contain newlines
        // Entry 1: description with trailing newline + empty bookmarks
        // Entry 2: description with trailing newline + has bookmark
        val entry1 = "wpvzxtyo${Z}wp${Z}aab9bd466295a9d1f3184daf585e15c7d3706d2c${Z}Did some stuff!\n${Z}${Z}true${Z}false${Z}false$Z"
        val entry2 = "pyusqzmk${Z}py${Z}7cc8ff2061b963efa3f17467360a420d780d625e${Z}Fix integration tests by using minimal Spring context configurations\n${Z}master${Z}false${Z}false${Z}false$Z"

        val logOutput = entry1 + entry2

        val entries = JujutsuLogParser.parseLog(logOutput)

        entries shouldHaveSize 2
        entries[0].changeId shouldBe "wpvzxtyo"
        entries[0].shortChangeIdPrefix shouldBe "wp"
        entries[0].description shouldBe "Did some stuff!\n"
        entries[0].bookmarks shouldHaveSize 0
        entries[0].isWorkingCopy shouldBe true

        entries[1].changeId shouldBe "pyusqzmk"
        entries[1].shortChangeIdPrefix shouldBe "py"
        entries[1].description shouldBe "Fix integration tests by using minimal Spring context configurations\n"
        entries[1].bookmarks shouldBe listOf("master")
        entries[1].isWorkingCopy shouldBe false
    }

    companion object {
        const val Z = "\u0000"
    }
}
