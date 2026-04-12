package `in`.kkkev.jjidea.ui.services

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsBundle

fun Project.showVcsMappingsSettings() =
    ShowSettingsUtil.getInstance().showSettingsDialog(
        this,
        VcsBundle.message("configurable.VcsDirectoryConfigurationPanel.display.name")
    )
