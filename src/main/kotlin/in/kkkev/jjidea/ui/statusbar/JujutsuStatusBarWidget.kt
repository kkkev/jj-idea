package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.dvcs.repo.Repository
import com.intellij.dvcs.repo.RepositoryImpl
import com.intellij.dvcs.ui.DvcsStatusWidget
import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.stateModel
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.initialisedJujutsuRepositories
import java.util.concurrent.ConcurrentHashMap

@Suppress("UnstableApiUsage")
class JujutsuStatusBarWidget(project: Project) :
    DvcsStatusWidget<JujutsuStatusBarWidget.WidgetRepository>(project, "Jujutsu") {
    private var listenersInstalled = false
    private val cachedEntries = ConcurrentHashMap<String, LogEntry>()

    companion object {
        private val log: Logger = Logger.getInstance(JujutsuStatusBarWidget::class.java)
        private const val MAX_DISPLAY_LEN = 40

        fun displayTextFor(entry: LogEntry): String {
            val sortedBookmarks = entry.bookmarks.sortedBy { it.isRemote }
            val text = when {
                sortedBookmarks.isNotEmpty() -> sortedBookmarks.joinToString(", ") { it.name }
                !entry.description.empty -> entry.description.summary
                else -> "(no description set)"
            }
            return if (text.length > MAX_DISPLAY_LEN) text.take(MAX_DISPLAY_LEN - 1) + "…" else text
        }
    }

    override fun ID() = JujutsuStatusBarWidgetFactory.ID

    override fun copy(): StatusBarWidget = JujutsuStatusBarWidget(project)

    override fun install(statusBar: StatusBar) {
        super.install(statusBar)
        if (listenersInstalled) return
        listenersInstalled = true
        preloadSwitcherItems()
        project.stateModel.initialisedRepositories.connect(this) { repos ->
            repos.values.forEach(JujutsuWorkingCopySwitcher::preload)
            updateLater()
        }
        project.stateModel.workingCopies.connect(this) { workingCopies ->
            cachedEntries.putAll(workingCopies)
            workingCopies.values.forEach { JujutsuWorkingCopySwitcher.preload(it.repo) }
            updateLater()
        }
        project.stateModel.logRefresh.connect(this) { _ ->
            preloadSwitcherItems()
            updateLater()
        }
    }

    override fun guessCurrentRepository(project: Project, file: VirtualFile?): WidgetRepository? {
        val repo = JujutsuWidgetSupport.currentRepository(project, file) ?: return null
        val entry = loadWorkingCopyEntry(repo) ?: run {
            log.debug("Widget: no entry for ${repo.directory.path}")
            return null
        }
        return WidgetRepository(project, repo, entry)
    }

    override fun getFullBranchName(repository: WidgetRepository): String = displayTextFor(repository.entry)

    override fun getIcon(repository: WidgetRepository) =
        if (repository.entry.hasConflict) AllIcons.General.Warning else AllIcons.Vcs.Branch

    override fun isMultiRoot(project: Project) = project.initialisedJujutsuRepositories.size > 1

    override fun getWidgetPopup(project: Project, repository: WidgetRepository): JBPopup {
        JujutsuWidgetSupport.rememberRecentRoot(project, repository.root.path)
        return JujutsuWorkingCopySwitcher.createPopup(repository.repo)
    }

    override fun rememberRecentRoot(path: String) {
        JujutsuWidgetSupport.rememberRecentRoot(project, path)
    }

    override fun getToolTip(repository: WidgetRepository?): String? {
        val entry = repository?.entry ?: return null
        return buildString {
            append("Jujutsu: ")
            if (entry.bookmarks.isNotEmpty()) {
                append(entry.bookmarks.sortedBy { it.isRemote }.joinToString(", ") { it.name })
                append(" ")
            }
            append("(${entry.id.short})")
            if (!entry.description.empty) append(" — ${entry.description.summary}")
            if (isMultiRoot(project)) append("\nRoot: ${repository.root.name}")
            append("\nClick to switch working copy")
        }
    }

    private fun preloadSwitcherItems() {
        project.initialisedJujutsuRepositories.forEach(JujutsuWorkingCopySwitcher::preload)
    }

    private fun loadWorkingCopyEntry(repo: JujutsuRepository): LogEntry? {
        val key = repo.directory.path
        project.stateModel.workingCopies.value[key]?.let { entry ->
            cachedEntries[key] = entry
            return entry
        }
        cachedEntries[key]?.let { return it }
        log.debug("Widget loadWorkingCopyEntry: miss for $key")
        return null
    }

    class WidgetRepository(
        project: Project,
        val repo: JujutsuRepository,
        val entry: LogEntry
    ) : RepositoryImpl(project, repo.directory) {
        override fun getState(): Repository.State =
            if (entry.hasConflict) Repository.State.MERGING else Repository.State.NORMAL

        override fun getCurrentBranchName(): String =
            entry.bookmarks.firstOrNull { !it.isRemote }?.name ?: entry.id.short

        override fun getVcs(): AbstractVcs =
            ProjectLevelVcsManager.getInstance(project).findVcsByName(JujutsuVcs.VCS_NAME)
                ?: error("Jujutsu VCS is not registered for project ${project.name}")

        override fun getCurrentRevision(): String = entry.commitId.full

        override fun update() = Unit

        override fun toLogString(): String = repo.toString()
    }
}
