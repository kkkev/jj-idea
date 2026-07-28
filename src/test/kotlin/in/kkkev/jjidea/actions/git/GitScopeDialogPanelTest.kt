package `in`.kkkev.jjidea.actions.git

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Remote
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression tests for jj-idea-tmmy: [GitPushDialog] and [GitFetchDialog] both build a
 * `buttonsGroup { }` of radio buttons bound via [bindScope] rather than the platform's
 * `ButtonsGroup.bind`. Passing a value to `radioButton(text, value)` in that unbound
 * configuration makes the platform throw `com.intellij.ui.dsl.UiDslException` from
 * `ButtonsGroupImpl.postInitUnbound` — the exception is thrown synchronously from `init()` inside
 * the dialog constructor, so simply constructing these dialogs (which `createCenterPanel` in
 * production code never exercised in a test before) reproduces and guards against the regression.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class GitScopeDialogPanelTest {
    private val project = projectFixture()

    @Test
    fun `push dialog constructs without throwing`() {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        val remote = Remote("origin")
        val data = GitPushDialog.DialogData(
            remotes = listOf(remote),
            trackedByRemote = mapOf(remote to listOf(Bookmark("main"))),
            allLocal = listOf(Bookmark("main"), Bookmark("feature", tracked = false))
        )

        val dialog = GitPushDialog(project.get(), mapOf(repo to data), repo)
        disposeDialog(dialog)
    }

    @Test
    fun `fetch dialog with multiple remotes constructs without throwing`() {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        val data = GitFetchDialog.FetchDialogData(remotes = listOf(Remote("origin"), Remote("github")))

        val dialog = GitFetchDialog(project.get(), mapOf(repo to data), repo)
        disposeDialog(dialog)
    }

    // ---- helpers ----

    private fun disposeDialog(dialog: DialogWrapper) {
        if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }
}
