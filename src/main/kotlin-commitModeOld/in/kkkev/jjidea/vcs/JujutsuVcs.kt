package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import com.intellij.vcs.commit.CommitMode
import `in`.kkkev.jjidea.settings.JujutsuSettings

/**
 * Concrete [JujutsuVcsBase], for platform builds before 261 (2025.2/2025.3), where
 * `com.intellij.vcs.commit.CommitMode`'s members are `useCommitToolWindow()`/
 * `hideLocalChangesTab()`/`disableDefaultCommitAction()` and `AbstractVcs.getForcedCommitMode`
 * takes no parameter. See the `kotlin-commitModeOld`/`kotlin-commitModeNew` source-set switch in
 * build.gradle.kts and [JujutsuVcsBase] (jj-idea-r5jf).
 */
class JujutsuVcs(project: Project) : JujutsuVcsBase(project) {
    /**
     * For jj-only projects, hides the standard Commit tool window and Local Changes tab in
     * favor of the plugin's jj-aware "Working copy" tool window (see [JujutsuHiddenCommitMode]).
     * Only consulted by the platform when Jujutsu is the *single* active VCS, so mixed
     * Jujutsu + Git projects are unaffected and keep the standard Commit panel for their
     * Git roots. Gated by [JujutsuSettings.hideStandardCommitToolWindow] so users who prefer
     * the standard panel can opt back in (jj-idea-wb5l).
     */
    override fun getForcedCommitMode(): CommitMode? =
        if (JujutsuSettings.getInstance(myProject).state.hideStandardCommitToolWindow) JujutsuHiddenCommitMode else null
}

/**
 * Hides the standard Commit tool window and its Local Changes tab for jj-only projects.
 *
 * Jujutsu auto-snapshots the working copy, so the platform's Commit dialog/tool window has
 * nothing meaningful to do here (see [JujutsuCheckinEnvironment]) and the plugin's own
 * "Working copy" tool window is the jj-aware equivalent. Returned from
 * [JujutsuVcs.getForcedCommitMode] when the user hasn't opted out via settings.
 *
 * Modeled on git4idea's `GitStagingAreaCommitMode`, the platform's own precedent for a VCS
 * that replaces the standard commit UI with its own panel.
 */
internal object JujutsuHiddenCommitMode : CommitMode {
    override fun useCommitToolWindow() = false
    override fun hideLocalChangesTab() = true
    override fun disableDefaultCommitAction() = true
}
