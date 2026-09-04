package `in`.kkkev.jjidea.ui.statusbar

import com.intellij.ide.ui.ToolbarSettings
import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.UISettingsListener
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.NewUI
import `in`.kkkev.jjidea.vcs.isJujutsu

/**
 * Status-bar fallback for [in.kkkev.jjidea.ui.toolbar.JujutsuBookmarkToolbarWidget], for the cases
 * where the main IDE toolbar isn't shown: Classic UI (no main toolbar exists at all), or New UI
 * with "Show main toolbar" turned off in Settings > Appearance. Mirrors git4idea's
 * `GitBranchWidget.Factory`/`SettingsListener` pair, which solves the identical problem for the
 * Git branch widget.
 *
 * Distinct from [JujutsuStatusBarWidgetFactory] (`Jujutsu.StatusBarWidget`), which is the
 * working-copy switcher and stays enabled unconditionally in the status bar regardless of main
 * toolbar visibility.
 */
class JujutsuBookmarkStatusBarWidgetFactory : StatusBarWidgetFactory {
    companion object {
        const val ID = "Jujutsu.BookmarkStatusBarWidget"
    }

    override fun getId() = ID
    override fun getDisplayName() = "Jujutsu Bookmark"

    override fun isAvailable(project: Project) =
        (NewUI.isEnabled() || isEnabledByDefault()) && project.isJujutsu

    override fun isEnabledByDefault(): Boolean {
        if (NewUI.isEnabled()) {
            // Show by default if the main toolbar is hidden via settings
            return !UISettings.getInstance().showNewMainToolbar
        }
        val toolbarSettings = ToolbarSettings.getInstance()
        return !toolbarSettings.isVisible || !toolbarSettings.isAvailable
    }

    override fun createWidget(project: Project) = JujutsuBookmarkStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()
    override fun canBeEnabledOn(statusBar: StatusBar) = true
}

/**
 * Toggles this widget live when the main toolbar's visibility setting changes, so switching
 * "Show main toolbar" on/off swaps between [in.kkkev.jjidea.ui.toolbar.JujutsuBookmarkToolbarWidget]
 * and this fallback without a restart. Mirrors `GitBranchWidget.SettingsListener`.
 */
class JujutsuBookmarkStatusBarSettingsListener(private val project: Project) : UISettingsListener {
    override fun uiSettingsChanged(uiSettings: UISettings) {
        val id = JujutsuBookmarkStatusBarWidgetFactory.ID
        if (!NewUI.isEnabled()) return

        // No pre-check against StatusBarWidgetSettings.isExplicitlyDisabled here: that class is
        // @ApiStatus.Internal (flagged by the marketplace's compatibility checker, unlike our own
        // verifyPlugin which deliberately excludes internal-API usage — see build.gradle.kts).
        // StatusBarWidgetsManager.updateWidget already checks it internally before showing the
        // widget, so skipping our own redundant check only costs an unnecessary updateWidget call
        // when the user has explicitly disabled this widget.
        //
        // Show/hide the bookmark widget if the main toolbar is hidden/shown via settings.
        // Deliberately calls the single-arg, no-default-parameters updateWidget(Class<...>)
        // overload rather than updateWidget(factory) — the latter has an optional
        // CoroutineContext parameter on newer platforms, which Kotlin compiles to a call on a
        // synthetic updateWidget$default bridge that doesn't exist on 2025.1/2025.2 (verifyPlugin
        // catches this as a NoSuchMethodError-risk COMPATIBILITY_PROBLEM).
        val extension = StatusBarWidgetFactory.EP_NAME.findExtension(JujutsuBookmarkStatusBarWidgetFactory::class.java)
        extension?.let { factory ->
            val manager = project.service<StatusBarWidgetsManager>()
            if (manager.wasWidgetCreated(id) != factory.isEnabledByDefault) {
                manager.updateWidget(JujutsuBookmarkStatusBarWidgetFactory::class.java)
            }
        }
    }
}
