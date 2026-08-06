package `in`.kkkev.jjidea.actions.git

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Remote
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
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

        val dialog = GitFetchDialog(project.get(), mapOf(repo to data))
        disposeDialog(dialog)
    }

    /**
     * Regression test for jj-idea-idm0: switching the Remote combo repopulates the bookmark combo
     * via `removeAllElements()` + `addAll(list)`, which leaves the combo's Swing selection null
     * (`addAll` doesn't select, unlike `addElement`) even though the backing `selectedBookmark`
     * field still points at the old remote's first bookmark. `doOKAction()` used to read that null
     * selection through `bindItem(...toNullableProperty())`, whose `!!` threw an NPE from inside
     * `applyFields()` — so `close()` was never reached and the Push button appeared to do nothing.
     */
    @Test
    fun `push dialog survives a remote switch then OK, and targets the new remote's bookmark`() {
        val repo = mockk<JujutsuRepository>(relaxed = true)
        val origin = Remote("origin")
        val github = Remote("github")
        val data = GitPushDialog.DialogData(
            remotes = listOf(origin, github),
            trackedByRemote = mapOf(
                origin to listOf(Bookmark("main")),
                github to listOf(Bookmark("mirror"))
            ),
            allLocal = listOf(Bookmark("main"), Bookmark("mirror"))
        )

        val dialog = GitPushDialog(project.get(), mapOf(repo to data), repo)
        try {
            val remoteComboBox = dialog.remoteComboBox
            remoteComboBox shouldNotBe null
            remoteComboBox!!.selectedItem = github
            remoteComboBox.actionListeners.forEach { it.actionPerformed(null) }

            // The bug: at this point the bookmark combo's Swing selection was null while
            // `selectedBookmark` still held the "main" bookmark from before the switch.
            dialog.bookmarkComboBox.selectedItem shouldBe Bookmark("mirror")

            dialog.specificBookmarkRadioButton!!.doClick()

            dialog.performOKAction()

            dialog.isOK shouldBe true
            dialog.result shouldNotBe null
            dialog.result!!.remote shouldBe github
            dialog.result!!.bookmark shouldBe Bookmark("mirror")
        } finally {
            disposeDialog(dialog)
        }
    }

    /**
     * Same desync as above, reached through the repository selector instead of the remote
     * selector: switching repos repopulates the remote combo, which used the same unsafe
     * `toNullableProperty()` binding.
     */
    @Test
    fun `fetch dialog survives a repository switch then OK, and targets the new repo's remote`() {
        val repoA = mockk<JujutsuRepository>(relaxed = true)
        val repoB = mockk<JujutsuRepository>(relaxed = true)
        every { repoA.displayName } returns "repoA"
        every { repoB.displayName } returns "repoB"
        val remoteA = Remote("origin")
        val remoteB = Remote("upstream")
        val allData = mapOf(
            repoA to GitFetchDialog.FetchDialogData(remotes = listOf(remoteA)),
            repoB to GitFetchDialog.FetchDialogData(remotes = listOf(remoteB))
        )

        val dialog = GitFetchDialog(project.get(), allData)
        try {
            val repoComboBox = dialog.repoComboBox
            repoComboBox shouldNotBe null
            repoComboBox!!.selectedItem = repoB
            repoComboBox.actionListeners.forEach { it.actionPerformed(null) }

            dialog.performOKAction()

            dialog.isOK shouldBe true
            dialog.result shouldNotBe null
            dialog.result!!.remote shouldBe remoteB
        } finally {
            disposeDialog(dialog)
        }
    }

    // ---- helpers ----

    private fun disposeDialog(dialog: DialogWrapper) {
        if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }
}
