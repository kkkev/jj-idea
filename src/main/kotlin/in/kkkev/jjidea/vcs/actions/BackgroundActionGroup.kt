package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionUpdateThreadAware
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import `in`.kkkev.jjidea.JujutsuBundle

class BackgroundActionGroup(vararg actions: AnAction) :
    DefaultActionGroup(*actions),
    ActionUpdateThreadAware.Recursive {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

@Suppress("ComponentNotRegistered")
open class PopupActionGroup(shortNameResourceKey: String, vararg actions: AnAction) :
    DefaultActionGroup(JujutsuBundle.message(shortNameResourceKey), actions.toList()),
    ActionUpdateThreadAware.Recursive {
    init {
        getTemplatePresentation().isPopupGroup = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
