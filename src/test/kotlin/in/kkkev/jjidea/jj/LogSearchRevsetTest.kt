package `in`.kkkev.jjidea.jj

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LogSearchRevsetTest {
    @Test
    fun `blank query returns null`() {
        logSearchRevset("", useRegex = false, matchCase = false, wholeWords = false).shouldBeNull()
        logSearchRevset("   ", useRegex = false, matchCase = false, wholeWords = false).shouldBeNull()
    }

    @Test
    fun `plain text uses case-insensitive substring by default`() {
        logSearchRevset("fix bug", useRegex = false, matchCase = false, wholeWords = false)!!.value shouldBe
            """description(substring-i:"fix bug") | author(substring-i:"fix bug")"""
    }

    @Test
    fun `matchCase drops the -i suffix`() {
        logSearchRevset("Fix Bug", useRegex = false, matchCase = true, wholeWords = false)!!.value shouldBe
            """description(substring:"Fix Bug") | author(substring:"Fix Bug")"""
    }

    @Test
    fun `useRegex passes the query through as a regex pattern`() {
        logSearchRevset("fix.*bug", useRegex = true, matchCase = false, wholeWords = false)!!.value shouldBe
            """description(regex-i:"fix.*bug") | author(regex-i:"fix.*bug")"""
    }

    @Test
    fun `useRegex with matchCase uses plain regex`() {
        logSearchRevset("Fix.*Bug", useRegex = true, matchCase = true, wholeWords = false)!!.value shouldBe
            """description(regex:"Fix.*Bug") | author(regex:"Fix.*Bug")"""
    }

    @Test
    fun `wholeWords without regex wraps the literal in word boundaries`() {
        // The `\b` word-boundary markers are backslashes that then need escaping again to embed
        // in the jj double-quoted string literal, hence `\\b` (an escaped backslash + "b") below.
        logSearchRevset("fix bug", useRegex = false, matchCase = false, wholeWords = true)!!.value shouldBe
            """description(regex-i:"\\bfix bug\\b") | author(regex-i:"\\bfix bug\\b")"""
    }

    @Test
    fun `wholeWords escapes regex metacharacters for the Rust regex crate, not Java-style quoting`() {
        logSearchRevset("a.b(c)", useRegex = false, matchCase = false, wholeWords = true)!!.value shouldBe
            """description(regex-i:"\\ba\\.b\\(c\\)\\b") | author(regex-i:"\\ba\\.b\\(c\\)\\b")"""
    }

    @Test
    fun `wholeWords is ignored when useRegex is set, matching LogFilterMatcher semantics`() {
        logSearchRevset("fix.*bug", useRegex = true, matchCase = false, wholeWords = true)!!.value shouldBe
            """description(regex-i:"fix.*bug") | author(regex-i:"fix.*bug")"""
    }

    @Test
    fun `quotes and backslashes in the query are escaped`() {
        logSearchRevset(
            """say "hi" \ there""",
            useRegex = false,
            matchCase = false,
            wholeWords = false
        )!!.value shouldBe
            """description(substring-i:"say \"hi\" \\ there") | author(substring-i:"say \"hi\" \\ there")"""
    }

    @Test
    fun `id-shaped query gains a leading present term`() {
        logSearchRevset("a1b2c3", useRegex = false, matchCase = false, wholeWords = false)!!.value shouldBe
            """present("a1b2c3") | description(substring-i:"a1b2c3") | author(substring-i:"a1b2c3")"""
    }

    @Test
    fun `bookmark-shaped query with slashes and dashes gains a present term`() {
        logSearchRevset("feature/foo-bar", useRegex = false, matchCase = false, wholeWords = false)!!.value shouldBe
            """present("feature/foo-bar") | description(substring-i:"feature/foo-bar") | """ +
            """author(substring-i:"feature/foo-bar")"""
    }

    @Test
    fun `free text with spaces does not get a present term`() {
        val result = logSearchRevset("fix the bug", useRegex = false, matchCase = false, wholeWords = false)!!.value
        (result.contains("present(")) shouldBe false
    }

    @Test
    fun `text containing revset metacharacters does not get a present term`() {
        for (query in listOf("fix.the.bug", "a..b", "a|b", "a(b)", "a\"b")) {
            val result = logSearchRevset(query, useRegex = false, matchCase = false, wholeWords = false)!!.value
            (result.contains("present(")) shouldBe false
        }
    }
}
