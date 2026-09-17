package `in`.kkkev.jjidea.ui.squash

import com.intellij.openapi.ui.DialogWrapper
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

/**
 * The dialog's title reflects which [SquashMode] built it - reported live against jj-idea-yvry's
 * drag gesture: before [SquashMode.PickDestination.fixedDestination] existed, a single-candidate
 * [SquashMode.PickDestination] always read "Squash into Parent" regardless of what the actual
 * destination was, because the title logic keyed only on `candidates != null`
 * ([hasPredefinedCandidates][SquashIntoDialog]) - true for both
 * [in.kkkev.jjidea.actions.filechange.SquashFilesAction]'s real parent-candidates flow and a
 * drag's single, arbitrary drop-target candidate.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class SquashIntoDialogTitleTest {
    private val project = projectFixture()

    private fun entry(id: String) = LogEntry(
        repo = mockRepo(project.get()),
        id = ChangeId(id, id),
        commitId = CommitId(id, id),
        underlyingDescription = ""
    )

    private fun disposeDialog(dialog: DialogWrapper) {
        if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }

    @Test
    fun `a free destination picker (no candidates) reads Squash from Here into`() {
        val source = entry("src1")
        val dialog = SquashIntoDialog(source.repo, SquashMode.PickDestination(listOf(source)), emptyList())

        dialog.title shouldBe JujutsuBundle.message("dialog.squash.into.title")
        disposeDialog(dialog)
    }

    @Test
    fun `real parent candidates (SquashFilesAction) reads Squash into Parent`() {
        val source = entry("src1")
        val parent = entry("par1")
        val dialog = SquashIntoDialog(
            source.repo,
            SquashMode.PickDestination(listOf(source), candidates = listOf(parent)),
            emptyList()
        )

        dialog.title shouldBe JujutsuBundle.message("dialog.squash.into.parent.title")
        disposeDialog(dialog)
    }

    @Test
    fun `a single fixed drop-target destination (drag gesture) reads Squash Into, not Squash into Parent`() {
        val source = entry("src1")
        val destination = entry("dest1")
        val dialog = SquashIntoDialog(
            source.repo,
            SquashMode.PickDestination(listOf(source), candidates = listOf(destination), fixedDestination = true),
            emptyList()
        )

        dialog.title shouldBe JujutsuBundle.message("dialog.squash.into.commit.title")
        disposeDialog(dialog)
    }

    @Test
    fun `PickSources mode always reads Squash Into Here from, regardless of candidates`() {
        val destination = entry("dest1")
        val dialog = SquashIntoDialog(destination.repo, SquashMode.PickSources(destination), emptyList())

        dialog.title shouldBe JujutsuBundle.message("dialog.squash.from.title")
        disposeDialog(dialog)
    }
}
