package `in`.kkkev.jjidea.ui.components

import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.RevisionExpression
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class RevisionChoiceTest {
    @Test
    fun `FreeForm resolves to a RevisionExpression of the typed text`() {
        val choice = RevisionChoice.FreeForm("zkptqxyz")
        choice.revision shouldBe RevisionExpression("zkptqxyz")
        choice.displayName shouldBe "zkptqxyz"
    }

    private fun entry(hidden: Boolean) = LogEntry(
        repo = mockk(),
        id = ChangeId("qpvuntsm", "q"),
        commitId = CommitId("abc123def456", "ab"),
        underlyingDescription = "desc",
        isHidden = hidden
    )

    @Test
    fun `a visible change is chosen by change id`() {
        val choice = RevisionChoice.Change(entry(hidden = false))
        choice.revision shouldBe ChangeId("qpvuntsm", "q")
    }

    @Test
    fun `a hidden change is chosen by commit id so it is not mistaken for the visible commit`() {
        val choice = RevisionChoice.Change(entry(hidden = true))
        choice.revision shouldBe CommitId("abc123def456", "ab")
        choice.displayName shouldBe "ab desc"
    }
}
