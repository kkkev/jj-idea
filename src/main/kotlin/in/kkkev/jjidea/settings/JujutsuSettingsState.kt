package `in`.kkkev.jjidea.settings

/**
 * Persistent state for Jujutsu plugin settings.
 *
 * [jjExecutablePath] is kept for migration deserialization only.
 * The canonical executable path lives in [JujutsuApplicationSettings].
 */
data class JujutsuSettingsState(
    var jjExecutablePath: String = "jj",
    var logChangeLimit: Int = 500,
    var logRevset: String = "all()",
    // Legacy int-keyed column widths kept for migration deserialization only (v3 migration)
    var customLogColumnWidths: MutableMap<Int, Int> = mutableMapOf(),
    // Global column widths kept for back-compat; v4 migration folds them into the default LogWindowConfig.
    var columnWidths: MutableMap<String, Int> = mutableMapOf(),
    var repositoryOverrides: MutableMap<String, RepositoryConfig> = mutableMapOf(),
    var settingsVersion: Int = 0,
    var squashDeleteEmptyAndMove: Boolean = false,
    var logWindows: MutableList<LogWindowConfig> = mutableListOf(),
    // jj-idea-wb5l: hides the standard Commit tool window / Local Changes tab for jj-only
    // projects in favor of the plugin's own "Working copy" tool window.
    var hideStandardCommitToolWindow: Boolean = true,
    // jj-idea-jqpe: tracks whether we've already auto-opened the Working copy tool window
    // once for this project, so it only happens on first discovery, not every startup.
    var workingCopyAutoOpened: Boolean = false,
    // jj-idea-tknb: off-switch for the log row hover tooltip.
    var showLogHoverTooltip: Boolean = true,
    // jj-idea-eyf1: off-switch for alternating-row striping in log/picker tables.
    var stripedLogRows: Boolean = true,
    // jj-idea-isnf: context window (ancestors/descendants) loaded when navigating to a
    // revision outside the currently loaded log. 0 loads only the target revision.
    var logContextWindow: Int = 10,
    // jj-idea-fmzr: which scope the Push dialog opens on, by
    // in.kkkev.jjidea.actions.git.GitPushDialog.PushScope name. Stored as a string rather than
    // the enum itself so this class stays free of a UI-package dependency and survives enum
    // reordering; an unrecognised value falls back to DEFAULT.
    var defaultPushScope: String = "DEFAULT",
    // jj-idea-fwea: base revision for editor gutter change markers and Annotate (GitHub #43).
    // WORKING_COPY_PARENT (the default) means "no override — behave exactly as before".
    var diffbaseStrategy: DiffbaseStrategy = DiffbaseStrategy.WORKING_COPY_PARENT,
    var customDiffbaseRevset: String = ""
)
