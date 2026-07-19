package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.keymap.KeymapManager
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import io.kotest.matchers.collections.shouldContain
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * Guards the plugin.xml keyboard-shortcut declaration behind double-click-follows-Enter
 * (jj-idea-th9h): double-clicking a log row invokes whichever action is bound to Enter, and by
 * default that must be [in.kkkev.jjidea.actions.filechange.ShowDiffAction]
 * (`Jujutsu.ShowChangesDiff`). If this binding is ever removed from plugin.xml, double-click
 * silently stops doing anything — this test fails loudly instead.
 *
 * Platform-tagged because loading the real plugin descriptor/keymap needs IJPGP's full platform
 * classpath, unlike the stripped unit-test classpath (see project memory on IJPGP test
 * infrastructure).
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuLogTableEnterActionTest {
    @Test
    fun `Enter is bound to Show Diff in the default keymap`() {
        val enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        val actionIds = KeymapManager.getInstance().activeKeymap.getActionIds(enter)

        actionIds.toList() shouldContain "Jujutsu.ShowChangesDiff"
    }
}
