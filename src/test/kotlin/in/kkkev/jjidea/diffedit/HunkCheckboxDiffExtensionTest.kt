package `in`.kkkev.jjidea.diffedit

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.ui.common.Hunk
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Platform tests for [HunkCheckboxDiffExtension]: session-gated install, icon state, click → callback. */
@Tag("platform")
@TestApplication
@RunInEdt
class HunkCheckboxDiffExtensionTest {
    private val project = projectFixture()
    private val disposable: Disposable = Disposer.newDisposable()

    private val before = "a\nb\nc\nd\ne\n"
    private val after = "a\nB\nc\nd\nE\n"
    private val hunks = listOf(Hunk(0, 1, 2, 1, 2), Hunk(1, 4, 5, 4, 5))

    @AfterEach
    fun tearDown() = Disposer.dispose(disposable)

    private fun request(session: HunkCheckboxSession?): SimpleDiffRequest {
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "t",
            factory.create(project.get(), before, PlainTextFileType.INSTANCE),
            factory.create(project.get(), after, PlainTextFileType.INSTANCE),
            "Before",
            "After"
        )
        if (session != null) request.putUserData(HunkCheckboxDiffExtension.HUNK_CHECKBOX_SESSION, session)
        return request
    }

    private fun show(request: SimpleDiffRequest) {
        val panel = DiffManager.getInstance().createRequestPanel(project.get(), disposable, null)
        WriteIntentReadAction.run { panel.setRequest(request) }
    }

    private fun gutterIcons(editor: EditorEx) =
        editor.markupModel.allHighlighters.mapNotNull { it.gutterIconRenderer?.icon }

    @Test
    fun `request without a session is a strict no-op - the viewer is never touched`() {
        val viewer = mockk<FrameDiffTool.DiffViewer>() // strict: any call would throw
        HunkCheckboxDiffExtension().onViewerCreated(viewer, mockk(), request(null))
    }

    @Test
    fun `session request installs one checkbox per hunk reflecting the ticked set`() {
        val session = HunkCheckboxSession(hunks, setOf(1), "tip") {}
        show(request(session))

        val viewer = session.attachedViewer.shouldNotBeNull()
        val icons = gutterIcons(viewer.getEditor(Side.RIGHT))
        icons.count { it === AllIcons.Diff.GutterCheckBox } shouldBe 1
        icons.count { it === AllIcons.Diff.GutterCheckBoxSelected } shouldBe 1
        gutterIcons(viewer.getEditor(Side.LEFT)).size shouldBe 0
    }

    @Test
    fun `toggle updates icons in place and reports the new set`() {
        var reported: Set<Int>? = null
        val session = HunkCheckboxSession(hunks, emptySet(), "tip") { reported = it }
        show(request(session))
        val viewer = session.attachedViewer.shouldNotBeNull()

        session.toggle(0)

        reported shouldBe setOf(0)
        gutterIcons(viewer.getEditor(Side.RIGHT)).count { it === AllIcons.Diff.GutterCheckBoxSelected } shouldBe 1

        session.toggle(0)
        reported shouldBe emptySet()
        gutterIcons(viewer.getEditor(Side.RIGHT)).count { it === AllIcons.Diff.GutterCheckBoxSelected } shouldBe 0
    }

    @Test
    fun `setChecked repaints without invoking the change callback`() {
        var calls = 0
        val session = HunkCheckboxSession(hunks, emptySet(), "tip") { calls++ }
        show(request(session))
        val viewer = session.attachedViewer.shouldNotBeNull()

        session.setChecked(setOf(0, 1))

        calls shouldBe 0
        gutterIcons(viewer.getEditor(Side.RIGHT)).count { it === AllIcons.Diff.GutterCheckBoxSelected } shouldBe 2
    }
}
