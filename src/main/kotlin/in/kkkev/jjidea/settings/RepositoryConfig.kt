package `in`.kkkev.jjidea.settings

/**
 * Per-repository configuration overrides.
 *
 * Fields are nullable: null means "use the project default".
 * Keyed by repository directory path in [JujutsuSettingsState.repositoryOverrides].
 */
data class RepositoryConfig(
    var logChangeLimit: Int? = null
)
