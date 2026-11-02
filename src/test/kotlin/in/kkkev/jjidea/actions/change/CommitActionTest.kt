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

/** [commitAction] (GitHub #157). */
@Tag("platform")
@TestApplication
@RunInEdt
class CommitActionTest {
    private val project = projectFixture()

    private fun entry() = LogEntry(
        repo = mockRepo(project.get()),
        id = ChangeId("abc123", "abc"),
        commitId = CommitId("abc123", "abc"),
        underlyingDescription = "",
        isWorkingCopy = true
    )

    @Test
    fun `reads Commit`() {
        val action = commitAction(project.get(), entry())

        action.templatePresentation.text shouldBe JujutsuBundle.message("log.action.commit")
        action.templatePresentation.text shouldBe "Commit..."
    }

    @Test
    fun `null target leaves nothing to act on`() {
        commitAction(project.get(), null).target.isEmpty() shouldBe true
    }
}
