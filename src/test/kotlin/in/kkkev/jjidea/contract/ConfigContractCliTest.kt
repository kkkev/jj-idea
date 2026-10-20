package `in`.kkkev.jjidea.contract

import `in`.kkkev.jjidea.jj.cli.tomlBasicString
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Path

@Tag("contract")
@RequiresJj
class ConfigContractCliTest : ConfigContractTest() {
    override fun createBackend(tempDir: Path) = JjCli(tempDir)

    // jj-idea-ehwm.6. Real jj only (the stub doesn't parse TOML or resolve aliases). Repo scope only: never touch the developer's real user config.
    private fun saveAlias(name: String, expression: String) =
        jj.run("config", "set", "--repo", "revset-aliases.$name", tomlBasicString(expression))

    @Test
    fun `saved revset alias with quotes resolves in log and round-trips through config get`() {
        val expression = """author(exact:"test@example.com") | @"""
        saveAlias("mine", expression).isSuccess shouldBe true

        jj.run("config", "get", "revset-aliases.mine").stdout.trim() shouldBe expression
        jj.run("config", "list", "--repo", "revset-aliases.mine").stdout.isBlank() shouldBe false
        jj.run("log", "-r", "mine", "--no-graph", "-T", "change_id ++ \"\\n\"").isSuccess shouldBe true
    }

    @Test
    fun `saving a revset alias again overwrites it`() {
        saveAlias("pick", "none()").isSuccess shouldBe true
        saveAlias("pick", "root()").isSuccess shouldBe true

        jj.run("config", "get", "revset-aliases.pick").stdout.trim() shouldBe "root()"
    }

    @Test
    fun `revset alias that looks like a TOML non-string is stored as a string`() {
        saveAlias("flag", "true").isSuccess shouldBe true

        jj.run("config", "list", "--repo", "revset-aliases.flag").stdout.trim() shouldBe
            "revset-aliases.flag = \"true\""
    }
}
