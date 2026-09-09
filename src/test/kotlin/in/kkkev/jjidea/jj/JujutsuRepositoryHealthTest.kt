package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * jj-idea-b65g: [classifyRepositoryFailure] must tell a stale workspace (fixable in-place by
 * `jj workspace update-stale`) apart from every other reason `jj log` can fail to read a repo
 * (jj-idea-9ife's broken/moved/incompatible-version case), so the right remedy can be offered.
 */
class JujutsuRepositoryHealthTest {
    @Test
    fun `jj's real stale working copy message classifies as Stale with its operation id`() {
        val message = "Error from jj log: Error: The working copy is stale " +
            "(not updated since operation 41f49686aa8e).\n" +
            "Hint: Run `jj workspace update-stale` to update it.\n" +
            "See https://docs.jj-vcs.dev/latest/working-copy/#stale-working-copy for more information."

        classifyRepositoryFailure(message) shouldBe RepositoryHealth.Stale(message, "41f49686aa8e")
    }

    @Test
    fun `jj's alternate 'could not read working copy's operation' message also classifies as Stale`() {
        // Verified empirically (jj 0.44, jj-idea-b65g): jj reports this - not "is stale" - when the
        // workspace's own on-disk operation pointer (.jj/working_copy/checkout) is itself unreadable/
        // corrupt, but still points to the same remedy. No operation id is present in this shape.
        val message = "Error from jj status: Error: Could not read working copy's operation.\n" +
            "Hint: Run `jj workspace update-stale` to recover.\n" +
            "See https://docs.jj-vcs.dev/latest/working-copy/#stale-working-copy for more information."

        classifyRepositoryFailure(message) shouldBe RepositoryHealth.Stale(message, null)
    }

    @Test
    fun `a broken store message classifies as Unreadable with no operation id`() {
        val message = "Error from jj log: Internal error: broken repo"

        classifyRepositoryFailure(message) shouldBe RepositoryHealth.Unreadable(message)
    }

    @Test
    fun `stale detection matches jj's remedy hint case-insensitively, not just the primary message line`() {
        classifyRepositoryFailure("Hint: run `JJ WORKSPACE UPDATE-STALE` to recover.") shouldBe
            RepositoryHealth.Stale("Hint: run `JJ WORKSPACE UPDATE-STALE` to recover.", null)
    }

    @Test
    fun `a divergent-operation message (op integrate, not update-stale) classifies as Unreadable`() {
        // Verified empirically (jj 0.44, jj-idea-b65g): a genuinely different failure - operation
        // log divergence from concurrent/racing writers - has a different remedy (`jj op integrate`)
        // and must not be offered "Update Stale Workspace", which wouldn't fix it.
        val message = "Internal error: The repo was loaded at operation 46cf1e13f88e, which seems to be " +
            "a sibling of the working copy's operation e64dd1c58d62\n" +
            "Hint: Run `jj op integrate e64dd1c58d62` to add the working copy's operation to the operation log."

        classifyRepositoryFailure(message) shouldBe RepositoryHealth.Unreadable(message)
    }
}
