package `in`.kkkev.jjidea.vcs

import com.intellij.vcs.commit.CommitMode

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
    override val isCommitTwEnabled = false
    override val isLocalChangesTabHidden = true
    override val isDefaultCommitActionDisabled = true
}
