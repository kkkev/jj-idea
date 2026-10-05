package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.runInEdtAndWait
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import javax.swing.JPanel

/**
 * Regression tests for jj-idea-wfz7: the platform calls [StatusBarWidget.install] on a worker
 * thread (`IdeStatusBarImpl.doInit`), so install must not touch Swing directly.
 * Deliberately not `@RunInEdt`.
 */
@Tag("platform")
@TestApplication
class JujutsuStatusBarWidgetInstallTest {
    private val project = projectFixture()

    private fun fakeStatusBar(component: JPanel): StatusBar =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(StatusBar::class.java)) { _, method, _ ->
            if (method.name == "getComponent") component else null
        } as StatusBar

    private fun installOffEdt(widget: JujutsuStatusBarWidget, bar: StatusBar) {
        ApplicationManager.getApplication().executeOnPooledThread { widget.install(bar) }.get()
    }

    private fun flushEdt() = runInEdtAndWait { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() }

    @Test
    fun `install from a background thread does not throw and wires the resize listener`() {
        val widget = JujutsuStatusBarWidget(project.get())
        val barComponent = JPanel().apply { setSize(1000, 20) }
        try {
            installOffEdt(widget, fakeStatusBar(barComponent))
            flushEdt()
            barComponent.componentListeners.size shouldBe 1
        } finally {
            runInEdt { Disposer.dispose(widget) }
        }
    }

    @Test
    fun `dispose before deferred install work runs does not leak the resize listener`() {
        val widget = JujutsuStatusBarWidget(project.get())
        val barComponent = JPanel().apply { setSize(1000, 20) }
        installOffEdt(widget, fakeStatusBar(barComponent))
        runInEdtAndWait { Disposer.dispose(widget) }
        flushEdt()
        barComponent.componentListeners.size shouldBe 0
    }
}
