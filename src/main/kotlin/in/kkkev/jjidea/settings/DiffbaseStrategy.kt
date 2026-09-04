package `in`.kkkev.jjidea.settings

/**
 * Strategy for choosing the base revision used for editor gutter change markers
 * (via [in.kkkev.jjidea.vcs.diffbase.DiffbaseContentLoader]) and file annotations
 * ([in.kkkev.jjidea.vcs.annotate.JujutsuAnnotationProvider]). Both consult the same
 * [in.kkkev.jjidea.vcs.diffbase.DiffbaseService] so they never disagree on the base
 * revision — see jj-idea-fwea / GitHub #43.
 *
 * A strategy is chosen from two places: Settings → Version Control → Jujutsu's Diff Base group
 * ([in.kkkev.jjidea.settings.JujutsuConfigurable], a permanent per-project/per-repo default) and
 * the "Set Diff Base" quick action ([in.kkkev.jjidea.actions.diffbase.SetDiffbaseAction], a fast
 * task-driven switch that writes the same per-repo setting — jj-idea-g1io).
 */
enum class DiffbaseStrategy {
    /** The working copy's parent (`@-`) — today's behaviour, and the default. */
    WORKING_COPY_PARENT,

    /** The latest immutable ancestor of `@-` (i.e. trunk). */
    IMMUTABLE_ANCESTOR,

    /**
     * The grandparent of the working copy (`@--`), floored at the latest immutable ancestor so it
     * never walks past trunk into immutable history. Requested alongside the other three
     * strategies in GitHub #43 but never shipped; see jj-idea-g1io.
     */
    PREVIOUS_COMMIT,

    /** A user-provided revset expression, see [in.kkkev.jjidea.settings.RepositoryConfig.customDiffbaseRevset]. */
    CUSTOM_REVSET;

    /**
     * The revset to resolve for this strategy, or `null` for [WORKING_COPY_PARENT], which means
     * "don't override — use the platform/plugin default of `@-`".
     */
    fun revset(customRevset: String): String? = when (this) {
        WORKING_COPY_PARENT -> null
        IMMUTABLE_ANCESTOR -> IMMUTABLE_ANCESTOR_REVSET
        PREVIOUS_COMMIT -> PREVIOUS_COMMIT_REVSET
        CUSTOM_REVSET -> customRevset.trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        const val IMMUTABLE_ANCESTOR_REVSET = "latest(ancestors(@-) & immutable())"
        const val PREVIOUS_COMMIT_REVSET = "latest(@-- | latest(ancestors(@-) & immutable()))"
    }
}
