package `in`.kkkev.jjidea.ui.oplog

import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.OperationEntry
import `in`.kkkev.jjidea.jj.OperationId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

class OperationRowActionsTest {
    private fun entry(id: String, root: Boolean = false, description: String = "op $id") =
        OperationEntry(
            OperationId(id),
            emptyList(),
            description,
            Instant.fromEpochSeconds(1788000000),
            "kevin",
            false,
            root,
            ""
        )

    @Test
    fun `revert is enabled for an ordinary operation`() {
        revertState(entry("a")).enabled shouldBe true
    }

    @Test
    fun `revert and restore are disabled with a reason on the root operation`() {
        val root = entry("0", root = true)

        revertState(root).enabled shouldBe false
        revertState(root).disabledReason shouldBe "The root operation cannot be reverted or restored"
        restoreState(root, head = entry("a")).enabled shouldBe false
    }

    @Test
    fun `both are disabled when nothing is selected`() {
        revertState(null).enabled shouldBe false
        restoreState(null, head = entry("a")).enabled shouldBe false
    }

    @Test
    fun `restoring to the head is disabled as a no-op but reverting it is allowed`() {
        val head = entry("a")

        restoreState(head, head).enabled shouldBe false
        revertState(head).enabled shouldBe true
    }

    @Test
    fun `restore to an earlier operation is enabled`() {
        restoreState(entry("b"), head = entry("a")).enabled shouldBe true
    }

    @Test
    fun `title is the first description line with placeholders for root and blank`() {
        entry("a", description = "rebase 3 commits\nextra detail").title() shouldBe "rebase 3 commits"
        entry("0", root = true, description = "").title() shouldBe "(repository created)"
        entry("b", description = "").title() shouldBe "(no description)"
    }

    @Test
    fun `restore confirmation names the target and how many later operations are discarded`() {
        val message = restoreConfirmMessage(entry("a", description = "describe commit"), laterCount = 4)

        message shouldContain "describe commit"
        message shouldContain "4 later operation(s)"
    }

    @Test
    fun `copy is disabled without a selection or with blank text, each with its reason`() {
        copyState(null, "x", "empty").disabledReason shouldBe "Select an operation"
        copyState(entry("a"), " ", "empty") shouldBe RowActionState(false, "empty")
        copyState(entry("a"), null, "empty").enabled shouldBe false
        copyState(entry("a"), "x", "empty").enabled shouldBe true
    }

    @Test
    fun `command drops jj's args prefix`() {
        entry("a").copy(args = "args: jj describe -m hi").command() shouldBe "jj describe -m hi"
        entry("a").copy(args = "").command() shouldBe ""
    }

    private val repoA = mockk<JujutsuRepository>(relaxed = true)
    private val repoB = mockk<JujutsuRepository>(relaxed = true)
    private fun row(repo: JujutsuRepository, id: String) = OperationRow(repo, entry(id))

    @Test
    fun `go to current targets the selected row's repo, else the only repo, else the top row`() {
        val a1 = row(repoA, "a1")
        val b1 = row(repoB, "b1")
        val a2 = row(repoA, "a2")
        val shown = listOf(b1, a1, a2)

        headRow(shown, a2) shouldBe a1
        headRow(listOf(a1, a2), null) shouldBe a1
        headRow(shown, null) shouldBe b1
        headRow(emptyList(), null) shouldBe null
    }

    @Test
    fun `go to current is disabled when empty or already on the target`() {
        val a1 = row(repoA, "a1")
        val a2 = row(repoA, "a2")

        goToHeadState(emptyList(), null).enabled shouldBe false
        goToHeadState(listOf(a1, a2), a1).disabledReason shouldBe "The current operation is already selected"
        goToHeadState(listOf(a1, a2), a2).enabled shouldBe true
        goToHeadState(listOf(a1, a2), null).enabled shouldBe true
    }
}
