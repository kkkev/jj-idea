package `in`.kkkev.jjidea.actions

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor

@Tag("platform")
@TestApplication
@RunInEdt
class CopyRefNameActionTest {
    @Test
    fun `copies the ref name to the clipboard`() {
        val action = copyRefNameAction("feature/ui@origin", RefNameKind.BOOKMARK)

        action.actionPerformed(TestActionEvent.createTestEvent(action))

        CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor) shouldBe "feature/ui@origin"
    }

    @Test
    fun `is disabled without a name`() {
        val action = copyRefNameAction(null, RefNameKind.TAG)
        val event = TestActionEvent.createTestEvent(action)

        action.update(event)

        event.presentation.isEnabled shouldBe false
    }
}
