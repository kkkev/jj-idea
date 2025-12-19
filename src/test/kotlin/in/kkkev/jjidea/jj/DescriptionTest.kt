package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DescriptionTest {
    @Test
    fun `empty description returns true for empty property`() {
        val description = Description("")
        description.empty shouldBe true
    }

    @Test
    fun `non-empty description returns false for empty property`() {
        val description = Description("Some description")
        description.empty shouldBe false
    }

    @Test
    fun `empty description displays placeholder text`() {
        val description = Description("")
        description.display shouldBe "(no description)"
    }

    @Test
    fun `non-empty description displays actual text`() {
        val description = Description("Actual description")
        description.display shouldBe "Actual description"
    }

    @Test
    fun `summary returns first line of single-line description`() {
        val description = Description("Single line")
        description.summary shouldBe "Single line"
    }

    @Test
    fun `summary returns first line of multi-line description`() {
        val description = Description("First line\nSecond line\nThird line")
        description.summary shouldBe "First line"
    }

    @Test
    fun `empty description summary returns placeholder text`() {
        val description = Description("")
        description.summary shouldBe "(no description)"
    }

    @Test
    fun `EMPTY companion object is truly empty`() {
        Description.EMPTY.empty shouldBe true
        Description.EMPTY.actual shouldBe ""
    }

    @Test
    fun `description with only newline has summary of empty string`() {
        val description = Description("\nSecond line")
        description.summary shouldBe ""
    }

    @Test
    fun `description with whitespace-only first line`() {
        val description = Description("   \nSecond line")
        description.summary shouldBe "   "
    }

    @Test
    fun `description with Windows-style line endings`() {
        val description = Description("First line\r\nSecond line")
        description.summary shouldBe "First line"
    }

    @Test
    fun `actual property returns original string unmodified`() {
        val original = "Test\nDescription\nWith\nLines"
        val description = Description(original)
        description.actual shouldBe original
    }
}
