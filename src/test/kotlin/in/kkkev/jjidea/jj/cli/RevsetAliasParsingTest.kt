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
}
