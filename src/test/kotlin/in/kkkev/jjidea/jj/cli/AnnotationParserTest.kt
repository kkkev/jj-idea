package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.AnnotationLine
import `in`.kkkev.jjidea.jj.CommitId
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class AnnotationParserTest {
    @Test
    fun `parse single line annotation`() {
        val output = "mnopqrst\u0000mnop\u0000\u0000abc123\u0000ab\u0000John Doe\u0000john@example.com\u0000" +
            "1768575623\u0000Initial commit\u0000println(\"Hello\")"

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].id.full shouldBe "mnopqrst"
        result[0].id.short shouldBe "mnop"
        result[0].commitId.full shouldBe "abc123"
        result[0].commitId.short shouldBe "ab"
        result[0].author.name shouldBe "John Doe"
        result[0].author.email shouldBe "john@example.com"
        result[0].description.summary shouldBe "Initial commit"
        result[0].lineContent shouldBe "println(\"Hello\")"
        result[0].lineNumber shouldBe 1
    }

    @Test
    fun `parse multiple lines`() {
        val line1 = "mnopqrst\u0000mnop\u0000\u0000abc123\u0000ab\u0000John Doe\u0000john@example.com\u0000" +
            "1768575623\u0000Initial commit\u0000println(\"Hello\")"
        val line2 = "uvwxyzab\u0000uvwx\u00005\u0000def456\u0000d\u0000Jane Smith\u0000jane@example.com\u0000" +
            "1768575623\u0000Add feature\u0000return 42"
        val output = "$line1\u0000$line2"

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 2

        result[0].id.short shouldBe "mnop"
        result[0].commitId.full shouldBe "abc123"
        result[0].commitId.short shouldBe "ab"
        result[0].author.name shouldBe "John Doe"
        result[0].lineContent shouldBe "println(\"Hello\")"
        result[0].lineNumber shouldBe 1

        result[1].id.short shouldBe "uvwx/5"
        result[1].commitId.full shouldBe "def456"
        result[1].commitId.short shouldBe "d"
        result[1].author.name shouldBe "Jane Smith"
        result[1].lineContent shouldBe "return 42"
        result[1].lineNumber shouldBe 2
    }

    @Test
    fun `parse annotation with empty description`() {
        val output = "mnopqrst\u0000mnop\u0000\u0000abc123\u0000ab\u0000John Doe\u0000john@example.com\u0000" +
            "1768575623\u0000\u0000println(\"Hello\")"

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].description.summary shouldBe "(no description)"
        result[0].lineContent shouldBe "println(\"Hello\")"
    }

    @Test
    fun `parse annotation with empty author email`() {
        val output = "mnopqrst\u0000mnop\u0000\u0000abc123\u0000ab\u0000John Doe\u0000\u0000" +
            "1768575623\u0000Initial commit\u0000println(\"Hello\")"

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].author.name shouldBe "John Doe"
        result[0].author.email shouldBe ""
    }

    @Test
    fun `parse annotation with empty author name`() {
        val output = "mnopqrst\u0000mnop\u0000\u0000abc123\u0000ab\u0000\u0000john@example.com\u0000" +
            "1768575623\u0000Initial commit\u0000println(\"Hello\")"

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].author.name shouldBe ""
        result[0].author.email shouldBe "john@example.com"
    }

    @Test
    fun `parse annotation with special characters in line content`() {
        val output = "mnopqrst\u0000mnop\u0000\u0000abc123\u0000ab\u0000John Doe\u0000john@example.com\u0000" +
            "1768575623\u0000Fix bug\u0000val x = \"hello|world\""

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].lineContent shouldBe "val x = \"hello|world\""
    }

    @Test
    fun `parse annotation with special characters in description`() {
        val output = "mnopqrst\u0000mnop\u0000\u0000abc123\u0000ab\u0000John Doe\u0000john@example.com\u0000" +
            "1768575623\u0000Fix: use grep | sort\u0000println(\"Hello\")"

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
        val output = "mnopqrst\u0000mnop\u0000\u0000abc123\u0000ab\u0000  John Doe  \u0000  john@example.com  \u0000" +
            "  1768575623  \u0000  Initial commit  \u0000  println(\"Hello\")  "

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        // Note: We don't trim fields in the parser, so whitespace is preserved
        result[0].author.name shouldBe "  John Doe  "
        result[0].author.email shouldBe "  john@example.com  "
    }

    @Test
    fun `annotation line tooltip contains key information`() {
        val output = "mnopqrst\u0000mnop\u0000\u0000abc123def456\u0000ab\u0000John Doe\u0000john@example.com\u0000" +
            "1768575623\u0000Initial commit\u0000println(\"Hello\")"

        val result = AnnotationParser.parse(output)
        val tooltip = result[0].getTooltip()

        tooltip shouldContain "mnop"
        tooltip shouldContain "abc123de"
        tooltip shouldContain "John Doe"
        tooltip shouldContain "john@example.com"
        tooltip shouldContain "Initial commit"
    }

    @Test
    fun `annotation line tooltip handles empty description`() {
        val output = "mnopqrst\u0000mnop\u0000\u0000abc123\u0000ab\u0000John Doe\u0000john@example.com\u0000" +
            "1768575623\u0000\u0000println(\"Hello\")"

        val result = AnnotationParser.parse(output)
        val tooltip = result[0].getTooltip()

        tooltip shouldContain "(no description)"
    }

    @Test
    fun `annotation line tooltip handles missing email`() {
        val output = "mnopqrst\u0000mnop\u0000\u0000abc123\u0000ab\u0000John Doe\u0000\u0000" +
            "1768575623\u0000Initial commit\u0000println(\"Hello\")"

        val result = AnnotationParser.parse(output)
        val tooltip = result[0].getTooltip()

        tooltip shouldContain "John Doe"
        // Email should not be shown when empty
        tooltip.count { it == '<' } shouldBe 0
    }

    @Test
    fun `parse annotation with unicode characters`() {
        val output = "mnopqrst\u0000mnop\u0000\u0000abc123\u0000ab\u0000José García\u0000jose@example.com\u0000" +
            "1768575623\u0000Añadir función\u0000println(\"¡Hola!\")"

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].author.name shouldBe "José García"
        result[0].description.summary shouldBe "Añadir función"
        result[0].lineContent shouldBe "println(\"¡Hola!\")"
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
        template shouldContain "description()"
        template shouldContain "content"

        // Should use null byte separator
        template shouldContain "\"\\0\""

        // Should use ++ for concatenation
        template shouldContain "++"
    }

    @Test
    fun `line numbers are sequential starting from 1`() {
        val line1 = "mnopqrst\u0000mnop\u0000\u0000abc123\u0000ab\u0000John Doe\u0000john@example.com\u0000" +
            "1768575623\u0000First\u0000line 1"
        val line2 = "uvwxyzab\u0000uvwx\u0000\u0000def456\u0000de\u0000Jane Smith\u0000jane@example.com\u0000" +
            "1768575623\u0000Second\u0000line 2"
        val line3 = "cdefghij\u0000cdef\u0000\u0000f001a4\u0000f\u0000Bob Jones\u0000bob@example.com\u0000" +
            "1768575623\u0000Third\u0000line 3"
        val output = "$line1\u0000$line2\u0000$line3"

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 3
        result[0].lineNumber shouldBe 1
        result[1].lineNumber shouldBe 2
        result[2].lineNumber shouldBe 3
    }

    @Test
    fun `parse annotation with very long change ID`() {
        val longChangeId = "mnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz"
        val output = "$longChangeId\u0000mnop\u0000\u0000abc123\u0000ab\u0000John Doe\u0000john@example.com\u0000" +
            "1768575623\u0000Initial commit\u0000println(\"Hello\")"

        val result = AnnotationParser.parse(output)

        result shouldHaveSize 1
        result[0].id.full shouldBe longChangeId
        result[0].id.short shouldBe "mnop"
    }

    @Test
    fun `empty annotation line helper creates valid empty line`() {
        val emptyLine = AnnotationLine.empty(42, "some content")

        emptyLine.lineNumber shouldBe 42
        emptyLine.lineContent shouldBe "some content"
        emptyLine.id.full shouldBe ""
        emptyLine.commitId shouldBe CommitId("")
        emptyLine.author.name shouldBe ""
        emptyLine.author.email shouldBe ""
        emptyLine.description.summary shouldBe "(no description)"
    }
}
