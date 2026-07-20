package `in`.kkkev.jjidea.ui.log

import com.intellij.vcs.log.VcsUser
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LogFilterMatcher] in isolation from the table model.
 *
 * Match semantics:
 * - [LogFilterMatcher.matches] (String) — substring, for free-text fields.
 * - [LogFilterMatcher.matches] (Shortenable) — prefix only, for ids.
 */
class LogFilterMatcherTest {
    @Nested
    inner class Create {
        @Test
        fun `blank query returns null`() {
            LogFilterMatcher.create("", useRegex = false, matchCase = false, wholeWords = false) shouldBe null
        }

        @Test
        fun `whitespace-only query returns null`() {
            LogFilterMatcher.create("   ", useRegex = false, matchCase = false, wholeWords = false) shouldBe null
        }
    }

    @Nested
    inner class `Substring matching (text)` {
        @Test
        fun `matches a mid-string fragment`() {
            val matcher = LogFilterMatcher.create("feature", useRegex = false, matchCase = false, wholeWords = false)!!

            matcher.matches("Add new feature") shouldBe true
            matcher.matches("Fix bug") shouldBe false
        }

        @Test
        fun `case insensitive by default`() {
            val matcher = LogFilterMatcher.create("FEATURE", useRegex = false, matchCase = false, wholeWords = false)!!

            matcher.matches("add feature") shouldBe true
        }

        @Test
        fun `case sensitive when enabled`() {
            val matcher = LogFilterMatcher.create("Feature", useRegex = false, matchCase = true, wholeWords = false)!!

            matcher.matches("Add Feature") shouldBe true
            matcher.matches("add feature") shouldBe false
        }

        @Test
        fun `whole words matches word boundaries only`() {
            val matcher = LogFilterMatcher.create("bug", useRegex = false, matchCase = false, wholeWords = true)!!

            matcher.matches("fix bug") shouldBe true
            matcher.matches("bugfix applied") shouldBe false
            matcher.matches("debug mode") shouldBe false
        }
    }

    @Nested
    inner class `Prefix matching (ids)` {
        @Test
        fun `matches a leading prefix`() {
            val matcher =
                LogFilterMatcher.create("abc123", useRegex = false, matchCase = false, wholeWords = false)!!

            matcher.matches(CommitId("abc123def456")) shouldBe true
        }

        @Test
        fun `does not match a mid-string fragment`() {
            val matcher = LogFilterMatcher.create("c123", useRegex = false, matchCase = false, wholeWords = false)!!

            // "c123" occurs inside "abc123def456" but is not a prefix.
            matcher.matches(CommitId("abc123def456")) shouldBe false
        }

        @Test
        fun `case insensitive by default`() {
            val matcher =
                LogFilterMatcher.create("ABC123", useRegex = false, matchCase = false, wholeWords = false)!!

            matcher.matches(CommitId("abc123def456")) shouldBe true
        }

        @Test
        fun `case sensitive when enabled`() {
            val matcher = LogFilterMatcher.create("ABC123", useRegex = false, matchCase = true, wholeWords = false)!!

            matcher.matches(CommitId("abc123def456")) shouldBe false
        }

        @Test
        fun `whole words option does not affect id prefix matching`() {
            val matcher = LogFilterMatcher.create("abc", useRegex = false, matchCase = false, wholeWords = true)!!

            matcher.matches(CommitId("abc123def456")) shouldBe true
        }

        @Test
        fun `divergent change id matches its user-visible short form`() {
            // full="abcdef/2", short="abc/2" - short is NOT a prefix of full.
            val id = ChangeId("abcdef", "abc", 2)
            id.full shouldBe "abcdef/2"
            id.short shouldBe "abc/2"

            LogFilterMatcher.create("abc/2", useRegex = false, matchCase = false, wholeWords = false)!!
                .matches(id) shouldBe true
        }

        @Test
        fun `divergent change id also matches a prefix of the full letters`() {
            val id = ChangeId("abcdef", "abc", 2)

            LogFilterMatcher.create("abcd", useRegex = false, matchCase = false, wholeWords = false)!!
                .matches(id) shouldBe true
        }

        @Test
        fun `divergent change id does not match the offset alone`() {
            val id = ChangeId("abcdef", "abc", 2)

            // "/2" is not a prefix of full ("abcdef/2") nor of short ("abc/2").
            LogFilterMatcher.create("/2", useRegex = false, matchCase = false, wholeWords = false)!!
                .matches(id) shouldBe false
        }
    }

    @Nested
    inner class `Regex mode` {
        @Test
        fun `substring match uses containsMatchIn`() {
            val matcher = LogFilterMatcher.create("feat|fix", useRegex = true, matchCase = false, wholeWords = false)!!

            matcher.matches("feat(ui): add button") shouldBe true
            matcher.matches("fix(api): handle error") shouldBe true
            matcher.matches("chore: update deps") shouldBe false
        }

        @Test
        fun `id prefix match is anchored at the start`() {
            val matcher = LogFilterMatcher.create("ab", useRegex = true, matchCase = false, wholeWords = false)!!

            matcher.matches(CommitId("abc123")) shouldBe true
            matcher.matches(CommitId("xabc123")) shouldBe false
        }

        @Test
        fun `respects case sensitivity`() {
            val matcher = LogFilterMatcher.create("FEATURE", useRegex = true, matchCase = true, wholeWords = false)!!

            matcher.matches("FEATURE add") shouldBe true
            matcher.matches("feature update") shouldBe false
        }

        @Test
        fun `invalid regex falls back to literal matching without throwing`() {
            // Unclosed bracket - invalid regex syntax.
            val matcher =
                LogFilterMatcher.create("[bracket", useRegex = true, matchCase = false, wholeWords = false)!!

            matcher.matches("test[bracket") shouldBe true
            matcher.matches("normal commit") shouldBe false
            matcher.matches(CommitId("[bracketabc")) shouldBe true
            matcher.matches(CommitId("abc[bracket")) shouldBe false
        }
    }

    @Nested
    inner class `matches(LogEntry)` {
        private val alice: VcsUser = VcsUserImpl("Alice", "alice@example.com")

        private fun entry(
            changeId: String = "abc123",
            description: String = "Test commit",
            author: VcsUser? = alice,
            commitId: String = "0000000000000000000000000000000000000000"
        ) = LogEntry(
            repo = mockk<JujutsuRepository>(),
            id = ChangeId(changeId, changeId, null),
            commitId = CommitId(commitId),
            underlyingDescription = description,
            author = author
        )

        @Test
        fun `matches by description`() {
            val matcher = LogFilterMatcher.create("feature", useRegex = false, matchCase = false, wholeWords = false)!!

            matcher.matches(entry(description = "Add feature")) shouldBe true
            matcher.matches(entry(description = "Fix bug")) shouldBe false
        }

        @Test
        fun `matches by author name or email`() {
            val matcher = LogFilterMatcher.create("alice", useRegex = false, matchCase = false, wholeWords = false)!!

            matcher.matches(entry(author = alice)) shouldBe true
            matcher.matches(entry(author = VcsUserImpl("Bob", "bob@example.com"))) shouldBe false
        }

        @Test
        fun `matches by change id prefix`() {
            val matcher = LogFilterMatcher.create("abc", useRegex = false, matchCase = false, wholeWords = false)!!

            matcher.matches(entry(changeId = "abc123")) shouldBe true
            matcher.matches(entry(changeId = "def456")) shouldBe false
        }

        @Test
        fun `matches by commit hash prefix`() {
            val matcher =
                LogFilterMatcher.create("8640b2e2", useRegex = false, matchCase = false, wholeWords = false)!!

            matcher.matches(
                entry(commitId = "8640b2e26aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            ) shouldBe true
            matcher.matches(
                entry(commitId = "1111111111111111111111111111111111111111")
            ) shouldBe false
        }

        @Test
        fun `does not match commit hash mid-string fragment`() {
            val matcher = LogFilterMatcher.create("b2e2", useRegex = false, matchCase = false, wholeWords = false)!!

            matcher.matches(
                entry(commitId = "8640b2e26aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            ) shouldBe false
        }
    }
}
