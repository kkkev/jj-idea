package `in`.kkkev.jjidea.actions.change

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.mockRepo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** [splitAction]'s two modes share one implementation and differ only in label (GitHub #132). */
@Tag("platform")
@TestApplication
@RunInEdt
class SplitActionTest {
    private val project = projectFixture()

    private fun entry() = LogEntry(
        repo = mockRepo(project.get()),
        id = ChangeId("abc123", "abc"),
        commitId = CommitId("abc123", "abc"),
        underlyingDescription = ""
    )

    @Test
    fun `default mode reads Split into New Child`() {
        val action = splitAction(project.get(), entry())

        action.templatePresentation.text shouldBe JujutsuBundle.message("log.action.split")
        action.templatePresentation.text shouldBe "Split into New Child..."
    }

    @Test
    fun `newParent mode reads Split into New Parent`() {
        val action = splitAction(project.get(), entry(), newParent = true)

        action.templatePresentation.text shouldBe "Split into New Parent..."
    }

    @Test
    fun `null target leaves both modes with nothing to act on`() {
        splitAction(project.get(), null).target.isEmpty() shouldBe true
        splitAction(project.get(), null, newParent = true).target.isEmpty() shouldBe true
    }
}
