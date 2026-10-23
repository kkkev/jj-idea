package `in`.kkkev.jjidea.jj.cli

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

class RevsetAliasParsingTest {
    @Test
    fun `symbol and function aliases`() {
        parseRevsetAliases(
            """
            revset-aliases.mywork = "mine() & ~empty()"
            revset-aliases."recent(n, who)" = "latest(who, n)"
            revset-aliases."nullary()" = "all()"
            """.trimIndent()
        ) shouldContainExactly listOf(
            RevsetAlias("mywork"),
            RevsetAlias("recent", true, listOf("n", "who")),
            RevsetAlias("nullary", true, emptyList())
        )
    }

    @Test
    fun `unrelated lines and blank output`() {
        parseRevsetAliases("") shouldContainExactly emptyList()
        parseRevsetAliases("user.name = \"x\"") shouldContainExactly emptyList()
    }

    @Test
    fun `sources are parsed, with repo-level aliases flagged`() {
        val us = "\u001f"
        val parsed = parseRevsetAliasSources(
            "revset-aliases.mine${us}user\n" +
                "revset-aliases.proj${us}repo\n" +
                "revset-aliases.\"f(x)\"${us}user\n"
        )

        parsed shouldContainExactly listOf(
            RevsetAlias("mine", source = "user"),
            RevsetAlias("proj", source = "repo"),
            RevsetAlias("f", true, listOf("x"), "user")
        )
        parsed.map { it.isRepoLevel } shouldContainExactly listOf(false, true, false)
    }
}
