package `in`.kkkev.jjidea.jj.cli

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class AnnotationParserTest {
    /**
     * Builds one annotate record: 11 fields joined by "\0", terminated by "\n" (mirroring how
     * jj's `content` field always ends with the source line's own trailing newline, which the
     * parser now uses as the record separator).
     */
    @Suppress("LongParameterList")
    private fun record(
        fullChangeId: String = "mnopqrst",
        shortChangeId: String = "mnop",
        changeOffset: String = "",
        fullCommitId: String = "abc123",
        shortCommitId: String = "ab",
        authorName: String = "John Doe",
        authorEmail: String = "john@example.com",
        authorTimestamp: String = "1768575623",
        description: String = "Initial commit",
        parentIds: String = "",
        content: String = "println(\"Hello\")"
    ): String {
        val fields = listOf(
            fullChangeId,
            shortChangeId,
            changeOffset,
            fullCommitId,
            shortCommitId,
            authorName,
            authorEmail,
            authorTimestamp,
            "\"$description\"",
            parentIds,
            content
        )
        return fields.joinToString("\u0000") + "\n"
    }

    @Test
    fun `parse single line annotation`() {
        val output = record()

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].id.full shouldBe "mnopqrst"
        result[0].id.short shouldBe "mnop"
        result[0].commitId.full shouldBe "abc123"
        result[0].commitId.short shouldBe "ab"
        result[0].author.name shouldBe "John Doe"
        result[0].author.email shouldBe "john@example.com"
        result[0].description.summary shouldBe "Initial commit"
        result[0].parentIds.shouldBeEmpty()
        result[0].lineContent shouldBe "println(\"Hello\")\n"
        result[0].lineNumber shouldBe 1
    }

    @Test
    fun `parse multiple lines`() {
        val output = record() +
            record(
                fullChangeId = "uvwxyzab",
                shortChangeId = "uvwx",
                changeOffset = "5",
                fullCommitId = "def456",
                shortCommitId = "d",
                authorName = "Jane Smith",
                authorEmail = "jane@example.com",
                description = "Add feature",
                content = "return 42"
            )

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 2

        result[0].id.short shouldBe "mnop"
        result[0].commitId.full shouldBe "abc123"
        result[0].commitId.short shouldBe "ab"
        result[0].author.name shouldBe "John Doe"
        result[0].lineContent shouldBe "println(\"Hello\")\n"
        result[0].lineNumber shouldBe 1

        result[1].id.short shouldBe "uvwx/5"
        result[1].commitId.full shouldBe "def456"
        result[1].commitId.short shouldBe "d"
        result[1].author.name shouldBe "Jane Smith"
        result[1].lineContent shouldBe "return 42\n"
        result[1].lineNumber shouldBe 2
    }

    @Test
    fun `parse annotation with empty description`() {
        val output = record(description = "")

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].description.summary shouldBe "(no description)"
        result[0].lineContent shouldBe "println(\"Hello\")\n"
    }

    @Test
    fun `parse annotation with empty author email`() {
        val output = record(authorEmail = "")

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].author.name shouldBe "John Doe"
        result[0].author.email shouldBe ""
    }

    @Test
    fun `parse annotation with empty author name`() {
        val output = record(authorName = "")

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].author.name shouldBe ""
        result[0].author.email shouldBe "john@example.com"
    }

    @Test
    fun `parse annotation with special characters in line content`() {
        val output = record(description = "Fix bug", content = "val x = \"hello|world\"")

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].lineContent shouldBe "val x = \"hello|world\"\n"
    }

    @Test
    fun `parse annotation with special characters in description`() {
        val output = record(description = "Fix: use grep | sort")

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].description.summary shouldBe "Fix: use grep | sort"
    }

    @Test
    fun `parse empty output`() {
        val result = AnnotationParser.parse("")

        result shouldHaveSize 0
    }

    @Test
    fun `parse blank output`() {
        val result = AnnotationParser.parse("   \n  \n  ")

        result shouldHaveSize 0
    }

    @Test
    fun `parse annotation with whitespace in fields`() {
        val output = record(
            authorName = "  John Doe  ",
            authorEmail = "  john@example.com  ",
            description = "  Initial commit  ",
            content = "  println(\"Hello\")  "
        )

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        // Note: We don't trim fields in the parser, so whitespace is preserved
        result[0].author.name shouldBe "  John Doe  "
        result[0].author.email shouldBe "  john@example.com  "
    }

    @Test
    fun `annotation line tooltip contains key information`() {
        val output = record(fullCommitId = "abc123def456")

        val result = AnnotationParser.parse(output)
        val tooltip = result[0].getHtmlTooltip()

        tooltip shouldContain "mnop" // change ID short
        tooltip shouldContain "c123de" // commit ID remainder (HTML splits bold short from grey tail)
        tooltip shouldContain "john@example.com"
        tooltip shouldContain "John"
        tooltip shouldContain "Initial"
    }

    @Test
    fun `annotation line tooltip handles empty description`() {
        val output = record(description = "")

        val result = AnnotationParser.parse(output)
        val tooltip = result[0].getHtmlTooltip()

        // Plain text, not a deliberate icon/chip gap - renders with an ordinary space now that
        // Formatters.escapeHtml no longer nbsp-ifies every space (jj-idea-myje / GitHub #77).
        tooltip shouldContain "(no description)"
    }

    @Test
    fun `annotation line tooltip handles missing email`() {
        val output = record(authorEmail = "")

        val result = AnnotationParser.parse(output)
        val tooltip = result[0].getHtmlTooltip()

        tooltip shouldContain "John"
        tooltip shouldNotContain "mailto:"
    }

    @Test
    fun `parse annotation with unicode characters`() {
        val output = record(
            authorName = "José García",
            authorEmail = "jose@example.com",
            description = "Añadir función",
            content = "println(\"¡Hola!\")"
        )

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].author.name shouldBe "José García"
        result[0].description.summary shouldBe "Añadir función"
        result[0].lineContent shouldBe "println(\"¡Hola!\")\n"
    }

    @Test
    fun `template format is correctly structured`() {
        val template = AnnotationParser.TEMPLATE

        // Should contain all required fields
        template shouldContain "change_id()"
        template shouldContain "change_id().shortest()"
        template shouldContain "commit_id()"
        template shouldContain "commit_id().shortest()"
        template shouldContain "author().name()"
        template shouldContain "author().email()"
        template shouldContain "description().escape_json()"
        template shouldContain "parents()"
        template shouldContain "content"

        // Should use null byte separator between fields
        template shouldContain "\"\\0\""

        // Should NOT append an extra separator after content: content's own trailing newline is
        // the record separator (jj-idea-3191 — content may itself contain a \0 byte).
        template shouldNotContain "content ++"

        // Should use ++ for concatenation
        template shouldContain "++"
    }

    @Test
    fun `line numbers are sequential starting from 1`() {
        val output = record(description = "First", content = "line 1") +
            record(
                fullChangeId = "uvwxyzab",
                shortChangeId = "uvwx",
                fullCommitId = "def456",
                shortCommitId = "de",
                authorName = "Jane Smith",
                authorEmail = "jane@example.com",
                description = "Second",
                content = "line 2"
            ) +
            record(
                fullChangeId = "cdefghij",
                shortChangeId = "cdef",
                fullCommitId = "f001a4",
                shortCommitId = "f",
                authorName = "Bob Jones",
                authorEmail = "bob@example.com",
                description = "Third",
                content = "line 3"
            )

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 3
        result[0].lineNumber shouldBe 1
        result[1].lineNumber shouldBe 2
        result[2].lineNumber shouldBe 3
    }

    @Test
    fun `parse annotation with very long change ID`() {
        val longChangeId = "mnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
        val output = record(fullChangeId = longChangeId)

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].id.full shouldBe longChangeId
        result[0].id.short shouldBe "mnop"
    }

    @Test
    fun `parse annotation with no parents (root commit)`() {
        val output = record()

        val result = AnnotationParser.parse(output)

        result[0].parentIds.shouldBeEmpty()
    }

    @Test
    fun `parse annotation with a single parent`() {
        val output = record(description = "Fix bug", parentIds = "parentid1")

        val result = AnnotationParser.parse(output)

        result[0].parentIds.map { it.full } shouldContainExactly listOf("parentid1")
    }

    @Test
    fun `parse annotation with multiple parents (merge commit)`() {
        val output = record(description = "Merge", parentIds = "parentid1,parentid2")

        val result = AnnotationParser.parse(output)

        result[0].parentIds.map { it.full } shouldContainExactly listOf("parentid1", "parentid2")
    }

    // --- jj-idea-3191 regression: content containing a null byte must not desynchronize fields ---

    @Test
    fun `parse annotation where content contains a null byte`() {
        // Simulates a source line like `stdout.split('\0')` (a literal null byte in the file
        // being annotated), followed by a normal line. Before the fix, the embedded \0 was
        // consumed as a field separator, shifting every later field of the *next* record.
        val badContent = "            stdout.split('\u0000')"
        val output = record(description = "Split", content = badContent) +
            record(
                fullChangeId = "uvwxyzab",
                shortChangeId = "uvwx",
                fullCommitId = "def456",
                shortCommitId = "d",
                authorName = "Jane Smith",
                authorEmail = "jane@example.com",
                description = "Next",
                content = "                .map { it.trim() }"
            )

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 2
        result[0].id.full shouldBe "mnopqrst"
        result[0].id.divergent shouldBe false
        result[0].lineContent shouldBe "$badContent\n"
        // The following record must parse unaffected by the embedded null byte.
        result[1].id.full shouldBe "uvwxyzab"
        result[1].id.short shouldBe "uvwx"
        result[1].lineContent shouldBe "                .map { it.trim() }\n"
    }

    @Test
    fun `parse annotation with blank source line`() {
        val output = record(content = "")

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        // A blank source line's content is just its terminating newline (matching jj's own
        // `content` field convention), not the empty string.
        result[0].lineContent shouldBe "\n"
    }

    @Test
    fun `parse annotation with multi-line description round-trips through escape_json`() {
        // "escape_json()" flattens a real multi-line description to a single-line JSON string
        // like "line1\nline2" (literal backslash-n, not a raw newline) before the record's
        // trailing newline separator.
        val output = record(description = "line1\\nline2")

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].description.display shouldBe "line1\nline2"
        result[0].description.summary shouldBe "line1"
    }

    @Test
    fun `unescapeJson decodes standard JSON escapes`() {
        AnnotationParser.unescapeJson("\"\"") shouldBe ""
        AnnotationParser.unescapeJson("\"hello\"") shouldBe "hello"
        AnnotationParser.unescapeJson("\"a\\\"b\"") shouldBe "a\"b"
        AnnotationParser.unescapeJson("\"a\\\\b\"") shouldBe "a\\b"
        AnnotationParser.unescapeJson("\"a\\nb\"") shouldBe "a\nb"
        AnnotationParser.unescapeJson("\"a\\tb\"") shouldBe "a\tb"
        AnnotationParser.unescapeJson("\"\\u0041\"") shouldBe "A"
    }
}
