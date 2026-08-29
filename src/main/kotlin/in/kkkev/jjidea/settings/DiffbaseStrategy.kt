package `in`.kkkev.jjidea.settings

/**
 * Strategy for choosing the base revision used for editor gutter change markers
 * (via [in.kkkev.jjidea.vcs.diffbase.DiffbaseContentLoader]) and file annotations
 * ([in.kkkev.jjidea.vcs.annotate.JujutsuAnnotationProvider]). Both consult the same
 * [in.kkkev.jjidea.vcs.diffbase.DiffbaseService] so they never disagree on the base
 * revision — see jj-idea-fwea / GitHub #43.
 */
enum class DiffbaseStrategy {
    /** The working copy's parent (`@-`) — today's behaviour, and the default. */
    WORKING_COPY_PARENT,

    /** The latest immutable ancestor of `@-` (i.e. trunk). */
    IMMUTABLE_ANCESTOR,

    /** A user-provided revset expression, see [in.kkkev.jjidea.settings.RepositoryConfig.customDiffbaseRevset]. */
    CUSTOM_REVSET;

    /**
     * The revset to resolve for this strategy, or `null` for [WORKING_COPY_PARENT], which means
     * "don't override — use the platform/plugin default of `@-`".
     */
    fun revset(customRevset: String): String? = when (this) {
        WORKING_COPY_PARENT -> null
        IMMUTABLE_ANCESTOR -> IMMUTABLE_ANCESTOR_REVSET
        CUSTOM_REVSET -> customRevset.trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        const val IMMUTABLE_ANCESTOR_REVSET = "latest(ancestors(@-) & immutable())"
    }
}
