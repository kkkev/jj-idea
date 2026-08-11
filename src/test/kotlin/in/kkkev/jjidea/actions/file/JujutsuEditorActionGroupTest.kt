package `in`.kkkev.jjidea.actions.file

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.TestApplication
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression tests for jj-idea-zmse: the platform's own "Annotate" action already places itself
 * directly into the diff viewer's popup menu (Diff.EditorPopupMenu), and Jujutsu.EditorGroup is
 * *also* added to that same menu, so without filtering, a diff-viewer right-click would show
 * "Annotate" twice — once at top level, once nested under "Jujutsu". The plain code editor has
 * no such top-level entry (Jujutsu deliberately isn't a StandardVcsGroup member, see plugin.xml),
 * so "Jujutsu > Annotate" must stay there as the only entry point.
 *
 * Platform test: fetches the group as registered from plugin.xml, since its static children
 * (including the "Annotate" reference) only exist once wired up by ActionManager.
 */
@Tag("platform")
@TestApplication
class JujutsuEditorActionGroupTest {
    // A raw DataContext (rather than SimpleDataContext, which eagerly runs the platform's async
    // data-validator pipeline against the editor's full API surface) so the mock only needs to
    // answer the one call getChildren actually makes: CommonDataKeys.EDITOR.
    private fun eventFor(editorKind: EditorKind): AnActionEvent {
        val editor = mockk<Editor> { every { this@mockk.editorKind } returns editorKind }
        val context = DataContext { dataId -> if (dataId == CommonDataKeys.EDITOR.name) editor else null }
        return TestActionEvent.createTestEvent(context)
    }

    private fun group() = ActionManager.getInstance().getAction("Jujutsu.EditorGroup") as JujutsuEditorActionGroup

    private fun ids(actions: Array<AnAction>) = actions.mapNotNull { ActionManager.getInstance().getId(it) }

    @Test
    fun `Annotate is present in the plain code editor context`() {
        ids(group().getChildren(eventFor(EditorKind.MAIN_EDITOR))) shouldContain "Annotate"
    }

    @Test
    fun `Annotate is filtered out in a diff editor, where the platform already places it at top level`() {
        ids(group().getChildren(eventFor(EditorKind.DIFF))) shouldNotContain "Annotate"
    }

    @Test
    fun `getChildren(null) does not crash and returns the unfiltered static children`() {
        ids(group().getChildren(null)) shouldContain "Annotate"
    }
}
