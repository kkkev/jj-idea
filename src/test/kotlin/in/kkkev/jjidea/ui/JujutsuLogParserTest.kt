package `in`.kkkev.jjidea.ui

import `in`.kkkev.jjidea.log.ChangeId
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
        val logLine = "qpvuntsm${Z}q${Z}abc123def456${Z}Add new feature${Z}${Z}${Z}false${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.changeId shouldBe ChangeId("qpvuntsm", 1)
        entry.commitId shouldBe "abc123def456"
        entry.description shouldBe "Add new feature"
        entry.bookmarks shouldHaveSize 0
        entry.parentIds shouldHaveSize 0
    }

    @Test
    fun `parse log entry with bookmarks`() {
        val logLine = "qpvuntsm${Z}q${Z}abc123def456${Z}Add new feature${Z}main,feature-branch${Z}${Z}false${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.bookmarks shouldHaveSize 2
        entry.bookmarks shouldBe listOf("main", "feature-branch")
    }

    @Test
    fun `parse working copy entry`() {
        val logLine = "qpvuntsm${Z}q${Z}abc123def456${Z}Work in progress${Z}${Z}${Z}true${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.isWorkingCopy shouldBe true
    }

    @Test
    fun `parse entry with conflict`() {
        val logLine = "qpvuntsm${Z}q${Z}abc123def456${Z}Conflicted change${Z}${Z}${Z}false${Z}true${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.hasConflict shouldBe true
    }

    @Test
    fun `parse empty commit`() {
        val logLine = "qpvuntsm${Z}q${Z}abc123def456${Z}${Z}${Z}${Z}false${Z}false${Z}true$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.isEmpty shouldBe true
        entry.description shouldBe ""
    }

    @Test
    fun `parse multiple log entries`() {
        // Entries concatenated with trailing \0 as separator (no newlines between entries)
        val entry1 = "qpvuntsm${Z}q${Z}abc123${Z}Add feature${Z}main${Z}${Z}true${Z}false${Z}false$Z"
        val entry2 = "rlvkpnrz${Z}rl${Z}def456${Z}Fix bug${Z}${Z}${Z}false${Z}false${Z}false$Z"
        val entry3 = "zxwvutsq${Z}z${Z}ghi789${Z}Initial commit${Z}${Z}${Z}false${Z}false${Z}false$Z"
        val logOutput = entry1 + entry2 + entry3

        val entries = JujutsuLogParser.parseLog(logOutput)

        entries shouldHaveSize 3
        entries[0].changeId shouldBe ChangeId("qpvuntsm", 1)
        entries[1].changeId shouldBe ChangeId("rlvkpnrz", 2)
        entries[2].changeId shouldBe ChangeId("zxwvutsq", 1)
    }

    @Test
    fun `parse log entry with multi-line description`() {
        // Description with embedded newline
        val logLine = "qpvuntsm${Z}qp${Z}abc123def456${Z}First line\nSecond line${Z}${Z}${Z}false${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.changeId shouldBe ChangeId("qpvuntsm", 2)
        entry.commitId shouldBe "abc123def456"
        entry.description shouldBe "First line\nSecond line"
        entry.bookmarks shouldHaveSize 0
    }

    @Test
    fun `parse multiple entries when one has multi-line description`() {
        // Entries concatenated; description contains actual newlines
        val entry1 = "qpvuntsm${Z}q${Z}abc123${Z}First line\nSecond line of same commit${Z}main${Z}${Z}true${Z}false${Z}false$Z"
        val entry2 = "rlvkpnrz${Z}rlv${Z}def456${Z}Single line description${Z}${Z}${Z}false${Z}false${Z}false$Z"
        val logOutput = entry1 + entry2

        val entries = JujutsuLogParser.parseLog(logOutput)

        entries shouldHaveSize 2
        entries[0].description shouldBe "First line\nSecond line of same commit"
        entries[1].description shouldBe "Single line description"
    }

    @Test
    fun `parse entry with multi-line description spanning three lines`() {
        val entry1 = "qpvuntsm${Z}q${Z}abc123${Z}Line 1\nLine 2\nLine 3${Z}main${Z}${Z}true${Z}false${Z}false$Z"
        val entry2 = "rlvkpnrz${Z}rl${Z}def456${Z}Another commit${Z}${Z}${Z}false${Z}false${Z}false$Z"
        val logOutput = entry1 + entry2

        val entries = JujutsuLogParser.parseLog(logOutput)

        entries shouldHaveSize 2
        entries[0].description shouldBe "Line 1\nLine 2\nLine 3"
        entries[0].changeId shouldBe ChangeId("qpvuntsm", 1)
        entries[1].description shouldBe "Another commit"
        entries[1].changeId shouldBe ChangeId("rlvkpnrz", 2)
    }

    @Test
    fun `parse entry with empty lines in multi-line description`() {
        // Description with blank line (actual double newline in description field)
        val logOutput =
            "qpvuntsm${Z}q${Z}abc123${Z}First paragraph\n\nSecond paragraph${Z}main${Z}${Z}true${Z}false${Z}false${Z}"

        val entries = JujutsuLogParser.parseLog(logOutput)

        entries shouldHaveSize 1
        entries[0].description shouldBe "First paragraph\n\nSecond paragraph"
    }

    @Test
    fun `parse entry with pipe character in description`() {
        // Description with | character should not break parsing when using null byte separator
        val logLine = "qpvuntsm${Z}q${Z}abc123def456${Z}Use grep | sort | uniq${Z}${Z}${Z}false${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.changeId shouldBe ChangeId("qpvuntsm", 1)
        entry.commitId shouldBe "abc123def456"
        entry.description shouldBe "Use grep | sort | uniq"
    }

    @Test
    fun `parse entries with descriptions containing pipe characters and newlines`() {
        // Test that both pipe characters and newlines work correctly with null byte separator
        val entry1 = "abc123${Z}a${Z}commit1${Z}Use command: ls | grep foo${Z}${Z}${Z}false${Z}false${Z}false$Z"
        val entry2 = "def456${Z}d${Z}commit2${Z}Multi-line\nWith pipes |\nAnd more text${Z}main${Z}${Z}true${Z}false${Z}false$Z"

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
        val logLine = "wnxouzzv${Z}w${Z}46da5a6ad44feb2fa45fc03309fab4c1f25ff05d${Z}${Z}${Z}${Z}true${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.changeId shouldBe ChangeId("wnxouzzv", 1)
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
        val entry1 = "wpvzxtyo${Z}wp${Z}aab9bd466295a9d1f3184daf585e15c7d3706d2c${Z}Did some stuff!\n${Z}${Z}${Z}true${Z}false${Z}false$Z"
        val entry2 = "pyusqzmk${Z}py${Z}7cc8ff2061b963efa3f17467360a420d780d625e${Z}Fix integration tests by using minimal Spring context configurations\n${Z}master${Z}${Z}false${Z}false${Z}false$Z"

        val logOutput = entry1 + entry2

        val entries = JujutsuLogParser.parseLog(logOutput)

        entries shouldHaveSize 2
        entries[0].changeId.full shouldBe "wpvzxtyo"
        entries[0].changeId.short shouldBe "wp"
        entries[0].description shouldBe "Did some stuff!\n"
        entries[0].bookmarks shouldHaveSize 0
        entries[0].isWorkingCopy shouldBe true

        entries[1].changeId.full shouldBe "pyusqzmk"
        entries[1].changeId.short shouldBe "py"
        entries[1].description shouldBe "Fix integration tests by using minimal Spring context configurations\n"
        entries[1].bookmarks shouldBe listOf("master")
        entries[1].isWorkingCopy shouldBe false
    }

    @Test
    fun `parse entry with single parent`() {
        val logLine = "qpvuntsm${Z}q${Z}abc123def456${Z}Add new feature${Z}${Z}plkvukqt~p${Z}false${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.changeId.full shouldBe "qpvuntsm"
        entry.parentIds shouldHaveSize 1
        entry.parentIds shouldBe listOf(ChangeId("plkvukqt", 1))
    }

    @Test
    fun `parse entry with multiple parents`() {
        val logLine = "qpvuntsm${Z}q${Z}abc123def456${Z}Merge commit${Z}main${Z}abcdefgh~abc, defghijk~def${Z}false${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.changeId shouldBe "qpvuntsm"
        entry.parentIds shouldHaveSize 2
        entry.parentIds shouldBe listOf(ChangeId("abcdefgh", 3), ChangeId("defghijk", 3))
    }

    @Test
    fun `parse entry with no parents (root commit)`() {
        val logLine = "zxwvutsq${Z}z${Z}abc123def456${Z}Initial commit${Z}${Z}${Z}false${Z}false${Z}false$Z"

        val entry = JujutsuLogParser.parseLogLine(logLine)

        entry.changeId shouldBe ChangeId("zxwvutsq", 1)
        entry.parentIds shouldHaveSize 0
    }

    @Test
    fun `parse multiple entries with various parent configurations`() {
        val entry1 = "qpvuntsm${Z}q${Z}abc123${Z}Feature work${Z}main${Z}plkvukqt~p${Z}true${Z}false${Z}false$Z"
        val entry2 = "rlvkpnrz${Z}rl${Z}def456${Z}Merge two branches${Z}${Z}abcdefgh~abc, defghijk~def${Z}false${Z}false${Z}false$Z"
        val entry3 = "zxwvutsq${Z}z${Z}ghi789${Z}Root commit${Z}${Z}${Z}false${Z}false${Z}false$Z"
        val logOutput = entry1 + entry2 + entry3

        val entries = JujutsuLogParser.parseLog(logOutput)

        entries shouldHaveSize 3
        entries[0].parentIds shouldBe listOf(ChangeId("plkvukqt", 1))
        entries[1].parentIds shouldBe listOf(ChangeId("abcdefgh", 3), ChangeId("defghijk", 3))
        entries[2].parentIds shouldHaveSize 0
    }

    @Test
    fun `getParentIdsDisplay returns formatted string`() {
        val entryWithParents = JujutsuLogEntry(
            changeId = ChangeId("qpvuntsm", 1),
            commitId = "abc123",
            description = "Test",
            parentIds = listOf(ChangeId("plkvukqt"), ChangeId("qrstuvwx"))
        )

        entryWithParents.getParentIdsDisplay() shouldBe "plkvukqt, qrstuvwx"

        val entryWithoutParents = JujutsuLogEntry(
            changeId = ChangeId("zxwvutsq", 1),
            commitId = "def456",
            description = "Root"
        )

        entryWithoutParents.getParentIdsDisplay() shouldBe ""
    }

    companion object {
        const val Z = "\u0000"
    }
}
