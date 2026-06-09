package `in`.kkkev.jjidea.ui.log

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import `in`.kkkev.jjidea.jj.JjAvailabilityChecker
import `in`.kkkev.jjidea.jj.JjAvailabilityStatus
import `in`.kkkev.jjidea.settings.JujutsuSettings
import `in`.kkkev.jjidea.settings.LogWindowConfig
import `in`.kkkev.jjidea.ui.common.JjNotInstalledPanel
import `in`.kkkev.jjidea.util.runInBackground
import `in`.kkkev.jjidea.util.runLater
import `in`.kkkev.jjidea.vcs.isJujutsu
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JPanel

/**
 * Service that manages opening and restoring custom Jujutsu log tabs.
 *
 * Supports multiple independent log windows, each backed by a [LogWindowConfig] that is persisted
 * across IDE restarts. Mirrors the multi-tab pattern from [JujutsuFileHistoryTabManager].
 */
@Service(Service.Level.PROJECT)
class JujutsuCustomLogTabManager(private val project: Project) : Disposable {
    private val log = Logger.getInstance(javaClass)

    private data class LogTabHandle(
        val config: LogWindowConfig,
        val content: Content,
        val panel: UnifiedJujutsuLogPanel,
        val wrapper: JPanel,
        val cardLayout: CardLayout,
        var notInstalledPanel: JPanel
    )

    /** Open tabs keyed by [LogWindowConfig.id]. */
    private val openTabs = mutableMapOf<String, LogTabHandle>()

    companion object {
        private const val CARD_LOG = "log"
        private const val CARD_NOT_INSTALLED = "notInstalled"

        /** User-data key marking every [Content] that belongs to a Jujutsu log window. */
        val JUJUTSU_LOG_CONTENT_KEY: Key<Boolean> = Key.create("jujutsu.log.content")

        fun getInstance(project: Project): JujutsuCustomLogTabManager = project.service()
    }

    /**
     * Opens or restores all persisted log windows.
     *
     * On first run creates a default window via [JujutsuSettings.ensureDefaultWindow].
     * Already-open tabs are skipped (dedup by config id). The default window is selected.
     */
    fun openCustomLogTab() {
        log.info("Opening Jujutsu log tab(s)")

        runInBackground {
            try {
                if (!project.isJujutsu) {
                    log.info("No initialised Jujutsu repositories found, skipping log tab creation")
                    return@runInBackground
                }

                val settings = JujutsuSettings.getInstance(project)
                val windows = settings.logWindows().ifEmpty { listOf(settings.ensureDefaultWindow()) }

                val changesViewContentManager = ChangesViewContentManager.getInstance(project)

                runLater {
                    for (config in windows) {
                        if (openTabs.containsKey(config.id)) continue
                        val handle = createTab(config)
                        openTabs[config.id] = handle
                        changesViewContentManager.addContent(handle.content)
                    }
                    // Select the default window (or first available)
                    val defaultTab = openTabs[JujutsuSettings.DEFAULT_LOG_WINDOW_ID] ?: openTabs.values.firstOrNull()
                    defaultTab?.let { changesViewContentManager.setSelectedContent(it.content) }
                }

                log.info("Jujutsu log tab(s) opened successfully")
            } catch (e: Exception) {
                log.error("Failed to open Jujutsu log tab(s)", e)
            }
        }
    }

    /**
     * Opens a new log tab for the given [config], persisting it to settings and selecting it.
     */
    fun openNewLogTab(config: LogWindowConfig) {
        log.info("Opening new log tab: ${config.name}")
        JujutsuSettings.getInstance(project).upsertLogWindow(config)

        val changesViewContentManager = ChangesViewContentManager.getInstance(project)
        runLater {
            val handle = createTab(config)
            openTabs[config.id] = handle
            changesViewContentManager.addContent(handle.content)
            changesViewContentManager.setSelectedContent(handle.content)
        }
    }

    private fun createTab(config: LogWindowConfig): LogTabHandle {
        val layout = CardLayout()
        val wrapper = JPanel(layout)

        val logPanel = UnifiedJujutsuLogPanel(project, config)
        Disposer.register(this, logPanel)
        wrapper.add(logPanel, CARD_LOG)

        val notInstalled = JPanel(BorderLayout())
        wrapper.add(notInstalled, CARD_NOT_INSTALLED)

        val isDefault = config.id == JujutsuSettings.DEFAULT_LOG_WINDOW_ID
        val content = ContentFactory.getInstance().createContent(wrapper, config.name, false).apply {
            isCloseable = !isDefault
            preferredFocusableComponent = wrapper
            putUserData(JUJUTSU_LOG_CONTENT_KEY, true)
            if (!isDefault) {
                setDisposer {
                    openTabs.remove(config.id)
                    JujutsuSettings.getInstance(project).removeLogWindow(config.id)
                    log.info("Log tab closed and removed from settings: ${config.name}")
                }
            }
        }

        // Subscribe to availability status (per-tab card switching)
        val checker = JjAvailabilityChecker.getInstance(project)
        checker.status.connect(this) { status -> updateTabForAvailabilityStatus(config.id, status) }
        updateTabForAvailabilityStatus(config.id, checker.status.value)

        return LogTabHandle(config, content, logPanel, wrapper, layout, notInstalled)
    }

    private fun updateTabForAvailabilityStatus(tabId: String, status: JjAvailabilityStatus) {
        val handle = openTabs[tabId] ?: return
        when (status) {
            is JjAvailabilityStatus.Available -> handle.cardLayout.show(handle.wrapper, CARD_LOG)
            else -> {
                handle.wrapper.remove(handle.notInstalledPanel)
                val fresh = JjNotInstalledPanel(project, status)
                handle.notInstalledPanel = fresh
                handle.wrapper.add(fresh, CARD_NOT_INSTALLED)
                handle.cardLayout.show(handle.wrapper, CARD_NOT_INSTALLED)
            }
        }
    }

    /**
     * Activate the Jujutsu log tab: select it and bring the tool window to the front.
     * Used when navigating to a commit from annotations or other entry points.
     */
    fun activateLogTab() {
        val handle = openTabs[JujutsuSettings.DEFAULT_LOG_WINDOW_ID] ?: openTabs.values.firstOrNull() ?: return
        val changesViewContentManager = ChangesViewContentManager.getInstance(project)
        changesViewContentManager.setSelectedContent(handle.content)
        ChangesViewContentManager.getToolWindowFor(project, handle.content.displayName)?.activate(null)
    }

    /**
     * Closes all open Jujutsu log tabs.
     *
     * Called when JJ roots are removed from the project so the native VCS log can return.
     */
    fun closeCustomLogTab() {
        log.info("Closing all Jujutsu log tabs")

        runLater {
            val contentManager = ChangesViewContentManager.getInstance(project)
            for (handle in openTabs.values) {
                try {
                    contentManager.removeContent(handle.content)
                } catch (e: Exception) {
                    log.warn("Failed to close log tab: ${handle.config.name}", e)
                }
            }
            openTabs.clear()
        }
    }

    /**
     * Renames the given [content] tab and persists the new name to settings.
     * Called from [in.kkkev.jjidea.actions.log.RenameJujutsuLogTabAction].
     */
    fun renameTab(content: Content, newName: String) {
        content.displayName = newName
        val handle = openTabs.values.firstOrNull { it.content === content } ?: return
        handle.config.name = newName
        JujutsuSettings.getInstance(project).upsertLogWindow(handle.config)
    }

    override fun dispose() {
        log.info("Disposing JujutsuCustomLogTabManager")
        openTabs.clear()
        // Child disposables (panels) are cleaned up automatically via Disposer
    }
}
