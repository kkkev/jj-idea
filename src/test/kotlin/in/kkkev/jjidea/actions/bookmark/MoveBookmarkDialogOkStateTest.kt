package `in`.kkkev.jjidea.actions.bookmark

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.BookmarkItem
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression coverage for jj-idea-vnd4 / GitHub #105: both Move Bookmark dialogs auto-select the
 * first eligible row in [MoveBookmarkDialog.rebuildList]/[MoveBookmarkToChangeDialog.rebuildList]
 * (via `selectFirstSelectable()`), but that call happened before the selection-listener that
 * enables OK was registered, so the initial auto-selection never enabled the button. The fix
 * calls `updateOkButton()` explicitly at the end of `rebuildList()`; these tests assert the
 * dialogs' initial `isOKActionEnabled` state instead of relying on a selection event firing.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class MoveBookmarkDialogOkStateTest {
    private val project = projectFixture()

    // ---- MoveBookmarkDialog ----

    @Test
    fun `OK is enabled immediately when a forward-movable bookmark is preselected`() {
        val bookmark = ClassifiedBookmark(
            BookmarkItem(Bookmark("main"), ChangeId("targetfull", "targ")),
            MoveDirection.FORWARD
        )
        val dialog = MoveBookmarkDialog(project.get(), listOf(bookmark))

        dialog.isOKActionEnabled shouldBe true
        disposeDialog(dialog)
    }

    @Test
    fun `OK is disabled when only a backward-or-sideways bookmark is available`() {
        val bookmark = ClassifiedBookmark(
            BookmarkItem(Bookmark("main"), ChangeId("targetfull", "targ")),
            MoveDirection.BACKWARD_OR_SIDEWAYS
        )
        val dialog = MoveBookmarkDialog(project.get(), listOf(bookmark))

        dialog.isOKActionEnabled shouldBe false
        disposeDialog(dialog)
    }

    @Test
    fun `OK is disabled when there are no candidate bookmarks`() {
        val dialog = MoveBookmarkDialog(project.get(), emptyList())

        dialog.isOKActionEnabled shouldBe false
        disposeDialog(dialog)
    }

    // ---- MoveBookmarkToChangeDialog ----

    @Test
    fun `Move to Change OK is enabled immediately when a forward-movable change is preselected`() {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        every { repo.project } returns project.get()
        val entry = createEntry(repo, "fwd1")

        val dialog = MoveBookmarkToChangeDialog(repo, listOf(entry to MoveDirection.FORWARD), currentId = null)

        dialog.isOKActionEnabled shouldBe true
        disposeDialog(dialog)
    }

    @Test
    fun `Move to Change OK is disabled when only a backward-or-sideways change is available`() {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        every { repo.project } returns project.get()
        val entry = createEntry(repo, "back1")

        val dialog = MoveBookmarkToChangeDialog(
            repo,
            listOf(entry to MoveDirection.BACKWARD_OR_SIDEWAYS),
            currentId = null
        )

        dialog.isOKActionEnabled shouldBe false
        disposeDialog(dialog)
    }

    @Test
    fun `Move to Change OK is disabled when there are no candidate changes`() {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        every { repo.project } returns project.get()

        val dialog = MoveBookmarkToChangeDialog(repo, emptyList(), currentId = null)

        dialog.isOKActionEnabled shouldBe false
        disposeDialog(dialog)
    }

    private fun createEntry(repo: JujutsuRepository, id: String) = LogEntry(
        repo = repo,
        id = ChangeId(id, id),
        commitId = CommitId(id, id),
        underlyingDescription = ""
    )

    private fun disposeDialog(dialog: DialogWrapper) {
        if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }
}
