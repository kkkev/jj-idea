package `in`.kkkev.jjidea.settings

/**
 * Per-repository configuration overrides.
 *
 * Fields are nullable: null means "use the project default".
 * Keyed by repository directory path in [JujutsuSettingsState.repositoryOverrides].
 */
data class RepositoryConfig(
    var logChangeLimit: Int? = null,
    var logRevset: String? = null,
    var disableIgnoredFileScanning: Boolean? = null,
    var logContextWindow: Int? = null,
    // jj-idea-fwea: per-repo diff-base override, see JujutsuSettings.diffbaseStrategy.
    var diffbaseStrategy: DiffbaseStrategy? = null,
    var customDiffbaseRevset: String? = null
) {
    fun isEmpty() =
        logChangeLimit == null &&
            logRevset == null &&
            disableIgnoredFileScanning == null &&
            logContextWindow == null &&
            diffbaseStrategy == null &&
            customDiffbaseRevset == null
}
