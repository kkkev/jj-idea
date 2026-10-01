package `in`.kkkev.jjidea.ui.components

import com.intellij.ide.DataManager
import com.intellij.ide.impl.HeadlessDataManager
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.vcs.VcsUserImpl
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor

/**
 * jj-idea-5zio, found in manual testing: the transfer handler alone is never reached by the real Cmd/Ctrl+C,
 * because the keymap sends it to `EditorCopy` (which copies each chip as a placeholder). What makes the real
 * shortcut work is the pane's own `COPY_PROVIDER` plus the platform Copy action registered on the pane - so
 * pin both, and that the provider copies chip text.
 */
@Tag("platform")
@TestApplication
@RunInEdt
class IconAwareHtmlPaneCopyWiringTest {
    private val project = projectFixture()
    private val disposable = Disposer.newDisposable()

    // Under @TestApplication, HeadlessDataManager ignores the component; restore real hierarchy-based
    // resolution so the pane's uiDataSnapshot is actually invoked (same as JujutsuChangesTreeUiDataTest).
    @BeforeEach
    fun useProductionDataManager() = HeadlessDataManager.fallbackToProductionDataManager(disposable)

    @AfterEach
    fun disposeDataManagerOverride() = Disposer.dispose(disposable)

    private fun paneWithChips() = IconAwareHtmlPane(project.get()).apply {
        text = htmlString {
            control("<body style='${Formatters.getBodyStyle()}'>", "</body>") {
                append("committed by")
                space()
                appendWithEmail(VcsUserImpl("Alice", "alice@example.com"))
                space()
                append(Bookmark("main"))
            }
        }
        setSize(2000, 1000)
        doLayout()
    }

    @Test
    fun `the pane supplies a COPY_PROVIDER that copies chip text`() {
        val pane = paneWithChips()
        pane.selectAll()

        val provider = PlatformDataKeys.COPY_PROVIDER.getData(DataManager.getInstance().getDataContext(pane))
        provider.shouldNotBeNull()
        val context = DataManager.getInstance().getDataContext(pane)
        provider.isCopyEnabled(context) shouldBe true
        provider.performCopy(context)

        val copied = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        copied.shouldNotBeNull()
        copied shouldContain "Alice <alice@example.com>"
        copied shouldContain "main"
    }

    @Test
    fun `copy is disabled with no selection`() {
        val pane = paneWithChips()
        val provider = PlatformDataKeys.COPY_PROVIDER.getData(DataManager.getInstance().getDataContext(pane))
        provider.shouldNotBeNull()

        provider.isCopyEnabled(DataManager.getInstance().getDataContext(pane)) shouldBe false
    }

    @Test
    fun `the platform Copy action is registered on the pane so it outranks the keymap's EditorCopy`() {
        val pane = paneWithChips()

        val registered = ActionUtil.getActions(pane)

        registered shouldHaveSize 1
        registered.single().shortcutSet.shortcuts.toList() shouldBe CommonShortcuts.getCopy().shortcuts.toList()
    }
}
