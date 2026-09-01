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
            """description(substring-i:"fix bug") | author(substring-i:"fix bug") | """ +
            """bookmarks(substring-i:"fix bug") | remote_bookmarks(substring-i:"fix bug")"""
    }

    @Test
    fun `matchCase drops the -i suffix`() {
        logSearchRevset("Fix Bug", useRegex = false, matchCase = true, wholeWords = false)!!.value shouldBe
            """description(substring:"Fix Bug") | author(substring:"Fix Bug") | """ +
            """bookmarks(substring:"Fix Bug") | remote_bookmarks(substring:"Fix Bug")"""
    }

    @Test
    fun `useRegex passes the query through as a regex pattern`() {
        logSearchRevset("fix.*bug", useRegex = true, matchCase = false, wholeWords = false)!!.value shouldBe
            """description(regex-i:"fix.*bug") | author(regex-i:"fix.*bug") | """ +
            """bookmarks(regex-i:"fix.*bug") | remote_bookmarks(regex-i:"fix.*bug")"""
    }

    @Test
    fun `useRegex with matchCase uses plain regex`() {
        logSearchRevset("Fix.*Bug", useRegex = true, matchCase = true, wholeWords = false)!!.value shouldBe
            """description(regex:"Fix.*Bug") | author(regex:"Fix.*Bug") | """ +
            """bookmarks(regex:"Fix.*Bug") | remote_bookmarks(regex:"Fix.*Bug")"""
    }

    @Test
    fun `wholeWords without regex wraps the literal in word boundaries`() {
        // The `\b` word-boundary markers are backslashes that then need escaping again to embed
        // in the jj double-quoted string literal, hence `\\b` (an escaped backslash + "b") below.
        logSearchRevset("fix bug", useRegex = false, matchCase = false, wholeWords = true)!!.value shouldBe
            """description(regex-i:"\\bfix bug\\b") | author(regex-i:"\\bfix bug\\b") | """ +
            """bookmarks(regex-i:"\\bfix bug\\b") | remote_bookmarks(regex-i:"\\bfix bug\\b")"""
    }

    @Test
    fun `wholeWords escapes regex metacharacters for the Rust regex crate, not Java-style quoting`() {
        logSearchRevset("a.b(c)", useRegex = false, matchCase = false, wholeWords = true)!!.value shouldBe
            """description(regex-i:"\\ba\\.b\\(c\\)\\b") | author(regex-i:"\\ba\\.b\\(c\\)\\b") | """ +
            """bookmarks(regex-i:"\\ba\\.b\\(c\\)\\b") | remote_bookmarks(regex-i:"\\ba\\.b\\(c\\)\\b")"""
    }

    @Test
    fun `wholeWords is ignored when useRegex is set, matching LogFilterMatcher semantics`() {
        logSearchRevset("fix.*bug", useRegex = true, matchCase = false, wholeWords = true)!!.value shouldBe
            """description(regex-i:"fix.*bug") | author(regex-i:"fix.*bug") | """ +
            """bookmarks(regex-i:"fix.*bug") | remote_bookmarks(regex-i:"fix.*bug")"""
    }

    @Test
    fun `quotes and backslashes in the query are escaped`() {
        logSearchRevset(
            """say "hi" \ there""",
            useRegex = false,
            matchCase = false,
            wholeWords = false
        )!!.value shouldBe
            """description(substring-i:"say \"hi\" \\ there") | author(substring-i:"say \"hi\" \\ there") | """ +
            """bookmarks(substring-i:"say \"hi\" \\ there") | remote_bookmarks(substring-i:"say \"hi\" \\ there")"""
    }

    @Test
    fun `id-shaped query gains a leading present term`() {
        logSearchRevset("a1b2c3", useRegex = false, matchCase = false, wholeWords = false)!!.value shouldBe
            """present("a1b2c3") | description(substring-i:"a1b2c3") | author(substring-i:"a1b2c3") | """ +
            """bookmarks(substring-i:"a1b2c3") | remote_bookmarks(substring-i:"a1b2c3")"""
    }

    @Test
    fun `bookmark-shaped query with slashes and dashes gains a present term`() {
        logSearchRevset("feature/foo-bar", useRegex = false, matchCase = false, wholeWords = false)!!.value shouldBe
            """present("feature/foo-bar") | description(substring-i:"feature/foo-bar") | """ +
            """author(substring-i:"feature/foo-bar") | bookmarks(substring-i:"feature/foo-bar") | """ +
            """remote_bookmarks(substring-i:"feature/foo-bar")"""
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

    @Test
    fun `includes bookmarks and remote_bookmarks terms`() {
        val result = logSearchRevset("fix the bug", useRegex = false, matchCase = false, wholeWords = false)!!.value
        result shouldBe
            """description(substring-i:"fix the bug") | author(substring-i:"fix the bug") | """ +
            """bookmarks(substring-i:"fix the bug") | remote_bookmarks(substring-i:"fix the bug")"""
    }
}
