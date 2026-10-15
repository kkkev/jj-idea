package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.Expression
import `in`.kkkev.jjidea.jj.Revset
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Unit coverage for [compileLogFilterRevset] (jj-idea-ehwm.2): one revset per repo from all log filters. */
class LogFilterRevsetTest {
    private val base = Expression("::@")

    private fun compile(state: LogFilterState, base: Revset = this.base) = compileLogFilterRevset(
        base,
        state
    )?.toString()

    @Test
    fun `no active filter compiles to null`() {
        compile(LogFilterState()) shouldBe null
        compile(LogFilterState(scope = "  ", searchText = " ")) shouldBe null
    }

    @Nested
    inner class Chips {
        @Test
        fun `scope is ANDed with the base`() {
            compile(LogFilterState(scope = "mine()")) shouldBe "(::@) & (mine())"
        }

        @Test
        fun `a default base is omitted rather than rendered`() {
            compile(LogFilterState(scope = "mine()"), Revset.Default) shouldBe "(mine())"
        }

        @Test
        fun `working copy reference is all ancestors of at`() {
            compile(LogFilterState(reference = LogFilterReference.WorkingCopy)) shouldBe "(::@) & (::(@))"
        }

        @Test
        fun `bookmark reference matches local and remote bookmarks of that name`() {
            compile(LogFilterState(reference = LogFilterReference.Bookmark("main")), Revset.Default) shouldBe
                "(::(bookmarks(exact:\"main\") | remote_bookmarks(exact:\"main\")))"
        }

        @Test
        fun `tag reference`() {
            compile(LogFilterState(reference = LogFilterReference.Tag("v1.0")), Revset.Default) shouldBe
                "(::(tags(exact:\"v1.0\")))"
        }

        @Test
        fun `authors are OR-ed by exact email, sorted for a stable query`() {
            compile(LogFilterState(authorEmails = setOf("b@x.com", "a@x.com")), Revset.Default) shouldBe
                "(author_email(exact:\"a@x.com\") | author_email(exact:\"b@x.com\"))"
        }

        @Test
        fun `date cutoff drops fractional seconds, which jj cannot parse`() {
            compile(LogFilterState(dateCutoff = Instant.parse("2026-10-08T15:49:48.895622Z")), Revset.Default) shouldBe
                "(author_date(after:\"2026-10-08T15:49:48Z\"))"
        }

        @Test
        fun `date cutoff is an author_date after clause`() {
            compile(LogFilterState(dateCutoff = Instant.parse("2026-10-01T12:00:00Z")), Revset.Default) shouldBe
                "(author_date(after:\"2026-10-01T12:00:00Z\"))"
        }

        @Test
        fun `paths are OR-ed files root clauses`() {
            compile(LogFilterState(paths = setOf("src/b.kt", "dir one")), Revset.Default) shouldBe
                "(files(root:\"dir one\") | files(root:\"src/b.kt\"))"
        }
    }

    @Nested
    inner class Search {
        @Test
        fun `text search reuses the log search revset`() {
            val term = compile(LogFilterState(searchText = "fix"), Revset.Default)!!
            term shouldContain "description(substring-i:\"fix\")"
            term shouldContain "author(substring-i:\"fix\")"
        }

        @Test
        fun `text search honours the regex, case and word toggles`() {
            compile(
                LogFilterState(searchText = "a.b", useRegex = true, matchCase = true),
                Revset.Default
            )!! shouldContain
                "description(regex:\"a.b\")"
            compile(LogFilterState(searchText = "a.b", wholeWords = true), Revset.Default)!! shouldContain
                "regex-i:\"\\\\ba\\\\.b\\\\b\""
        }

        @Test
        fun `revset mode passes the text through untouched`() {
            compile(
                LogFilterState(searchText = " heads(::@) ", revsetMode = true, useRegex = true),
                Revset.Default
            ) shouldBe
                "(heads(::@))"
        }
    }

    @Test
    fun `escapes quotes and backslashes in every quoted value`() {
        compile(LogFilterState(paths = setOf("a\"b\\c")), Revset.Default) shouldBe "(files(root:\"a\\\"b\\\\c\"))"
        compile(LogFilterState(reference = LogFilterReference.Tag("t\"x")), Revset.Default)!! shouldContain "\"t\\\"x\""
    }

    @Test
    fun `all filters combine in a fixed order, each parenthesised so a union cannot leak`() {
        val result = compile(
            LogFilterState(
                scope = "a | b",
                reference = LogFilterReference.Bookmark("main"),
                authorEmails = setOf("a@x.com", "b@x.com"),
                paths = setOf("p"),
                searchText = "x",
                revsetMode = true
            )
        )
        result shouldBe
            "(::@) & (a | b) & (::(bookmarks(exact:\"main\") | remote_bookmarks(exact:\"main\"))) & " +
            "(author_email(exact:\"a@x.com\") | author_email(exact:\"b@x.com\")) & (files(root:\"p\")) & (x)"
    }
}
