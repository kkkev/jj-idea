package `in`.kkkev.jjidea.actions.tag

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import `in`.kkkev.jjidea.jj.GitRemote
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Tag
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Group-level shape of [pushTagAction]. Per-child jj-version gating goes through
 * `JjFeature.isSupportedIn(project)` (needs a live availability checker) and is covered by
 * `JjFeatureTest` for the version comparison itself.
 */
class PushTagActionTest {
    private val repo = mockk<JujutsuRepository>(relaxed = true)
    private lateinit var presentation: Presentation
    private lateinit var event: AnActionEvent

    @BeforeEach
    fun setup() {
        presentation = Presentation()
        event = mockk(relaxed = true)
        every { event.presentation } returns presentation
    }

    private fun remotes(vararg names: String) = names.map { GitRemote(it, "https://example.com/$it.git") }

    @Test
    fun `hidden with no Git remotes`() {
        every { repo.cachedGitRemotes } returns emptyList()
        pushTagAction(repo, Tag("v1")).also { it.update(event) }
        presentation.isVisible shouldBe false
    }

    @Test
    fun `transparent with one remote`() {
        every { repo.cachedGitRemotes } returns remotes("origin")
        val group = pushTagAction(repo, Tag("v1"))
        group.update(event)
        presentation.isVisible shouldBe true
        group.isPopup shouldBe false
        group.getChildren(event).single().templatePresentation.text shouldBe "Push Tag 'v1' to origin..."
    }

    @Test
    fun `submenu with one child per remote when there are several`() {
        every { repo.cachedGitRemotes } returns remotes("origin", "github")
        val group = pushTagAction(repo, Tag("v1"))
        group.update(event)
        group.isPopup shouldBe true
        presentation.text shouldBe "Push Tag 'v1' to"
        group.getChildren(event).map { it.templatePresentation.text } shouldBe
            listOf("Push Tag 'v1' to origin...", "Push Tag 'v1' to github...")
    }
}
