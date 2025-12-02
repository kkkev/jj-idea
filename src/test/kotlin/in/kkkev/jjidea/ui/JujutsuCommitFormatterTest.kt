package `in`.kkkev.jjidea.ui

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Tests for formatting commit identifiers in the jj way
 * - Short unique prefix should be distinguishable (bold in UI)
 * - Working copy should show both commit name and @
 */
class JujutsuCommitFormatterTest {

    @Test
    fun `format change ID with short prefix`() {
        val fullChangeId = "qpvuntsm"
        val shortPrefix = "qp"

        val formatted = JujutsuCommitFormatter.formatChangeId(fullChangeId, shortPrefix)

        formatted.shortPart shouldBe "qp"
        formatted.restPart shouldBe "vuntsm"
        formatted.full shouldBe "qpvuntsm"
    }

    @Test
    fun `format change ID when entire ID is short prefix`() {
        val fullChangeId = "ab"
        val shortPrefix = "ab"

        val formatted = JujutsuCommitFormatter.formatChangeId(fullChangeId, shortPrefix)

        formatted.shortPart shouldBe "ab"
        formatted.restPart shouldBe ""
        formatted.full shouldBe "ab"
    }

    @Test
    fun `format working copy shows both description and @`() {
        val changeId = "qpvuntsm"
        val shortPrefix = "qp"
        val description = "Add new feature"

        val formatted = JujutsuCommitFormatter.formatWorkingCopy(changeId, shortPrefix, description)

        formatted shouldContain "@"
        formatted shouldContain changeId
        formatted shouldContain description
    }

    @Test
    fun `format working copy with no description shows empty marker`() {
        val changeId = "qpvuntsm"
        val shortPrefix = "qp"

        val formatted = JujutsuCommitFormatter.formatWorkingCopy(changeId, shortPrefix, "")

        formatted shouldContain "@"
        formatted shouldContain "(empty)"
    }

    // Note: toHtml tests require IntelliJ Platform classes and should be tested in full integration tests
}
