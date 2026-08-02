package `in`.kkkev.jjidea.vcs

import com.intellij.openapi.project.Project
import com.intellij.vcs.commit.CommitMode
import `in`.kkkev.jjidea.settings.JujutsuSettings

/**
 * Concrete [JujutsuVcsBase], for platform build 261 (2026.1) onward, where
 * `AbstractVcs.getForcedCommitMode` takes a `CommitMode` parameter. See the
 * `kotlin-commitModeOld`/`kotlin-commitModeNew` source-set switch in build.gradle.kts and
 * [JujutsuVcsBase] (jj-idea-r5jf).
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
    override fun getForcedCommitMode(originalMode: CommitMode): CommitMode? =
        if (JujutsuSettings.getInstance(myProject).state.hideStandardCommitToolWindow) JujutsuHiddenCommitMode else null
}
