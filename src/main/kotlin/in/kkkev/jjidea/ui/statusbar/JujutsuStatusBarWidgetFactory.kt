package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class JujutsuStatusBarWidgetFactory : StatusBarWidgetFactory {
    companion object {
        const val ID = "Jujutsu.StatusBarWidget"
    }

    override fun getId() = ID
    override fun getDisplayName() = "Jujutsu Working Copy"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project) = JujutsuStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
    override fun canBeEnabledOn(statusBar: StatusBar) = true
}
