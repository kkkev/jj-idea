package `in`.kkkev.jjidea.jj.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * jj-idea-b65g: [workspaceUpdateStaleArgs] builds `jj workspace update-stale`, classified
 * [Reversibility.IRREVERSIBLE] since it repairs this workspace's own on-disk state - reverting it
 * would simply re-stale the workspace it just fixed, so no undo affordance should be offered.
 */
class CliExecutorWorkspaceTest {
    @Test
    fun `workspace update-stale`() {
        val result = workspaceUpdateStaleArgs()

        result.args shouldBe listOf("workspace", "update-stale")
        result.reversibility shouldBe Reversibility.IRREVERSIBLE
    }
}
