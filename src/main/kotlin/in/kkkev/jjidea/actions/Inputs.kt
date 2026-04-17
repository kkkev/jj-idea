package `in`.kkkev.jjidea.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Description

fun Project.requestDescription(
    resourceKeyPrefix: String,
    initial: Description = Description.EMPTY,
    vararg messageParams: Any
) = Messages.showMultilineInputDialog(
    this,
    JujutsuBundle.message("$resourceKeyPrefix.message", *messageParams),
    JujutsuBundle.message("$resourceKeyPrefix.title"),
    initial.actual,
    null,
    null
)?.let(::Description)
