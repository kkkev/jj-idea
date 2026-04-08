package `in`.kkkev.jjidea.ui.services

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

fun Project.showVcsMappingsSettings() =
    ShowSettingsUtil.getInstance().showSettingsDialog(this, "project.propVCSSupport.Mappings")
