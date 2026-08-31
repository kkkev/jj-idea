package `in`.kkkev.jjidea.ui.squash

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.CommitId
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.ui.components.iconAwareTooltip
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression test for jj-idea-2md7: [SquashIntoDialog.pickerTable] must render row tooltips
 * through [in.kkkev.jjidea.ui.components.IconAwareHtmlPane] (via
 * [in.kkkev.jjidea.ui.components.installIconAwareTableTooltip]), not a plain Swing tooltip which
 * paints bookmark/tag chip `<img>` markup as a broken image.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class SquashIntoDialogTooltipTest {
    private val project = projectFixture()
    private val repo = mockk<JujutsuRepository>(relaxed = true)

    @Test
    fun `picker table has the icon-aware tooltip installed`() {
        val dest = createEntry("dest1")
        val src = createEntry("src1")
        val dialog = SquashIntoDialog(project.get(), dest.repo, SquashMode.PickSources(dest, listOf(src)), emptyList())

        dialog.pickerTable.iconAwareTooltip().shouldNotBeNull()

        disposeDialog(dialog)
    }

    private fun createEntry(id: String) = LogEntry(
        repo = repo,
        id = ChangeId(id, id),
        commitId = CommitId(id, id),
        underlyingDescription = "desc"
    )

    private fun disposeDialog(dialog: DialogWrapper) {
        if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }
}
