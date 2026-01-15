package `in`.kkkev.jjidea.vcs.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.Description

fun Project.requestDescription(resourceKeyPrefix: String, initial: Description = Description.EMPTY) =
    Messages.showMultilineInputDialog(
        this,
        JujutsuBundle.message("${resourceKeyPrefix}.message"),
        JujutsuBundle.message("${resourceKeyPrefix}.title"),
        initial.actual,
        null,
        null
    )?.let(::Description)
