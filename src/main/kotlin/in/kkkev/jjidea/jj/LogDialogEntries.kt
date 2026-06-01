package `in`.kkkev.jjidea.jj

import com.intellij.openapi.project.Project
import `in`.kkkev.jjidea.settings.JujutsuSettings

/**
 * Loads log entries for dialog pickers (destination/source selectors in rebase, squash, etc.)
 * using the user's configured revset and limit — the same data source as the main log table.
 */
fun loadRepoEntries(project: Project, repo: JujutsuRepository): List<LogEntry> {
    val settings = JujutsuSettings.getInstance(project)
    val revsetSetting = settings.logRevset(repo)
    val revset: Revset = if (revsetSetting.isBlank()) Revset.Default else Expression(revsetSetting)
    val limit = settings.logChangeLimit(repo)
    return repo.logService.getLogBasic(revset, limit = limit).getOrElse { emptyList() }
}
