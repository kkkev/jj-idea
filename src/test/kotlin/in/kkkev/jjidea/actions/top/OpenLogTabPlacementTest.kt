package `in`.kkkev.jjidea.actions.top

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.testFramework.junit5.TestApplication
import `in`.kkkev.jjidea.actions.ManagedActions
import `in`.kkkev.jjidea.actions.id
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression tests for jj-idea-biqp (GitHub #118): `Jujutsu.OpenLogTab` used to be registered
 * with no action group at all, reachable only via Find Action or a manually-bound shortcut.
 *
 * Platform test: resolves the groups as registered from plugin.xml, since their static children
 * (including the `Jujutsu.OpenLogTab` reference) only exist once wired up by ActionManager.
 */
@Tag("platform")
@TestApplication
class OpenLogTabPlacementTest {
    private fun group(id: String) = ManagedActions[id] as ActionGroup

    private fun ids(group: ActionGroup) =
        group.getChildren(null).mapNotNull { it.id }

    @Test
    fun `Jujutsu OpenLogTab is the first entry in the Jujutsu main menu group`() {
        val children = ids(group("Jujutsu.MainMenuGroup"))
        children shouldContain "Jujutsu.OpenLogTab"
        children.first() shouldBe "Jujutsu.OpenLogTab"
    }

    @Test
    fun `Jujutsu OpenLogTab is present in the VCS-aware operations popup`() {
        ids(group("Vcs.Operations.Popup.VcsAware")) shouldContain "Jujutsu.OpenLogTab"
    }

    @Test
    fun `Jujutsu OpenLogTab is not in the non-VCS-aware popup group, which only renders outside an active VCS`() {
        val nonVcsAware = ids(group("Vcs.Operations.Popup.NonVcsAware"))
        nonVcsAware shouldNotContain "Jujutsu.OpenLogTab"
        // Sanity check: Jujutsu.Init legitimately lives there (shown only pre-jj-init).
        nonVcsAware shouldContain "Jujutsu.Init"
    }
}
