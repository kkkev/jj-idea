package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.actionSystem.DefaultActionGroup
import `in`.kkkev.jjidea.JujutsuBundle
import javax.swing.Icon

fun DefaultActionGroup.addPopup(resourceKeyPrefix: String, icon: Icon, builder: DefaultActionGroup.() -> Unit) = add(
    DefaultActionGroup(
        JujutsuBundle.message(resourceKeyPrefix),
        JujutsuBundle.message("$resourceKeyPrefix.description"),
        icon
    ).apply {
        isPopup = true
        builder()
    }
)
