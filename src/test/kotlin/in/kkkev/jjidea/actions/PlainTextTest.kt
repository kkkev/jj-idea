package `in`.kkkev.jjidea.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.testFramework.junit5.TestApplication
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** GitHub #156 (jj-idea-ln8e): names containing `_`/`&` must not be eaten as mnemonic markers. */
@Tag("platform")
@TestApplication
class PlainTextTest {
    @Test
    fun `setPlainText keeps underscores and ampersands`() {
        Presentation().apply { setPlainText("my_feature & a_b") }.text shouldBe "my_feature & a_b"
    }

    @Test
    fun `plain setText would swallow the underscore`() {
        Presentation().apply { text = "my_feature" }.text shouldBe "myfeature"
    }

    @Test
    fun `named action label keeps underscores in the bookmark name`() {
        val action = nullAndDumbAwareAction("my_feature", "action.bookmark.forget", AllIcons.Actions.Copy) {}

        action.templatePresentation.text shouldBe "Forget 'my_feature'"
    }
}
