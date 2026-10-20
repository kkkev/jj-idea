package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Revset
import `in`.kkkev.jjidea.jj.mockRepo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * jj-idea-ehwm.6: building the dialog is what first exercised its Kotlin UI DSL layout - the scope radio
 * buttons crashed with "Button group must be defined before using radio button" - so construction itself is
 * the regression test, with the title as a sanity check that init() completed.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class SaveRevsetAliasDialogTest {
    private val project = projectFixture()

    @Test
    fun `dialog builds its panel including the scope radio buttons`() {
        val dialog = SaveRevsetAliasDialog(
            repos = listOf(mockRepo(project.get())),
            expression = "mine() & mutable()",
            baseFor = { Revset.Default },
            onSaved = {}
        )

        dialog.title shouldBe JujutsuBundle.message("log.filter.revset.alias.title")
        if (!dialog.isDisposed) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
    }
}
