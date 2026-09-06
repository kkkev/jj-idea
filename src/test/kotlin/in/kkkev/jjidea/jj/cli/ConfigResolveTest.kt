package `in`.kkkev.jjidea.jj.cli

import `in`.kkkev.jjidea.jj.CommandExecutor
import `in`.kkkev.jjidea.jj.CommandExecutor.CommandResult
import `in`.kkkev.jjidea.jj.CommandExecutor.ConfigScope
import `in`.kkkev.jjidea.jj.cli.Config.Key.USER_NAME
import `in`.kkkev.jjidea.jj.commandResult
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Tests for [Config.ScopedConfig.resolve] — jj-idea-i0e6's value-plus-provenance lookup. Drives
 * [Config] over a fake [CommandExecutor] (the `object : CommandExecutor by mockk(relaxed = true)`
 * pattern used in [in.kkkev.jjidea.actions.bookmark.MoveBookmarkDirectionTest]) rather than
 * [in.kkkev.jjidea.contract.StubCommandExecutor]/[in.kkkev.jjidea.contract.JjStub]: the stub's
 * `config list` has no concept of `-T` templates or conditional scopes, so it can't stand in for
 * a real provenance response - see [ScopedIdentityContractCliTest] for that end-to-end coverage.
 */
class ConfigResolveTest {
    private fun fakeExecutor(getResult: CommandResult, detailedResult: CommandResult): CommandExecutor =
        object : CommandExecutor by mockk(relaxed = true) {
            override fun configGet(key: String) = getResult
            override fun configListDetailed(key: String, scope: ConfigScope?) = detailedResult
        }

    @Test
    fun `parses value, source, and path out of the delimited template line`() {
        val executor = fakeExecutor(
            getResult = commandResult(0, stdout = "Kevin Thomas\n"),
            detailedResult = commandResult(
                0,
                stdout = "\"Kevin Thomas\"$CONFIG_PROVENANCE_DELIMITER" +
                    "user$CONFIG_PROVENANCE_DELIMITER" +
                    "/Users/kevin/.config/jj/config.toml\n"
            )
        )

        Config(executor).effective.resolve(USER_NAME) shouldBe
            Config.Resolved("Kevin Thomas", "user", "/Users/kevin/.config/jj/config.toml")
    }

    @Test
    fun `blank path field resolves to null path, not an empty string`() {
        val executor = fakeExecutor(
            getResult = commandResult(0, stdout = "built-in default\n"),
            detailedResult = commandResult(
                0,
                stdout = "\"built-in default\"${CONFIG_PROVENANCE_DELIMITER}default$CONFIG_PROVENANCE_DELIMITER\n"
            )
        )

        Config(executor).effective.resolve(USER_NAME) shouldBe
            Config.Resolved("built-in default", "default", null)
    }

    @Test
    fun `configListDetailed failing degrades to value with no provenance, not a missing row`() {
        val executor = fakeExecutor(
            getResult = commandResult(0, stdout = "Kevin Thomas\n"),
            detailedResult = commandResult(1, stderr = "old jj: unknown template keyword 'source'")
        )

        Config(executor).effective.resolve(USER_NAME) shouldBe Config.Resolved("Kevin Thomas", null, null)
    }

    @Test
    fun `unparseable provenance output degrades to value with no provenance`() {
        val executor = fakeExecutor(
            getResult = commandResult(0, stdout = "Kevin Thomas\n"),
            detailedResult = commandResult(0, stdout = "not delimited at all\n")
        )

        Config(executor).effective.resolve(USER_NAME) shouldBe Config.Resolved("Kevin Thomas", null, null)
    }

    @Test
    fun `configGet failing means the key isn't set - resolve returns null, not a Resolved`() {
        val executor = fakeExecutor(
            getResult = commandResult(1, stderr = "No matching config key"),
            detailedResult = commandResult(0)
        )

        Config(executor).effective.resolve(USER_NAME) shouldBe null
    }
}
