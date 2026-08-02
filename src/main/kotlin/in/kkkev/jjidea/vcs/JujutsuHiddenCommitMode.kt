package `in`.kkkev.jjidea.vcs

import com.intellij.vcs.commit.CommitMode
import java.lang.reflect.Proxy

/**
 * Hides the standard Commit tool window and its Local Changes tab for jj-only projects.
 *
 * Jujutsu auto-snapshots the working copy, so the platform's Commit dialog/tool window has
 * nothing meaningful to do here (see [JujutsuCheckinEnvironment]) and the plugin's own
 * "Working copy" tool window is the jj-aware equivalent. Returned from `JujutsuVcs
 * .getForcedCommitMode` when the user hasn't opted out via settings (jj-idea-wb5l).
 *
 * Modeled on git4idea's `GitStagingAreaCommitMode`, the platform's own precedent for a VCS
 * that replaces the standard commit UI with its own panel.
 *
 * Built via a JDK dynamic [Proxy] rather than a class statically `: CommitMode` (jj-idea-r5jf
 * follow-up). `com.intellij.vcs.commit.CommitMode`'s members were renamed between platform build
 * 253 (2025.3) and 261 (2026.1) with no compat shim - a class *statically* implementing either
 * shape is missing the other shape's abstract member(s), which the JetBrains Plugin Verifier
 * correctly flags as a real binary-incompatibility ("Abstract method CommitMode
 * .useCommitToolWindow() is not implemented") against whichever range it doesn't match, since the
 * plugin is published as a single build (always compiled against the default/newest
 * `platformVersion`) that has to work against the *running* IDE's actual `CommitMode` shape, not
 * just whichever shape it happened to be compiled against. A proxy resolves each method purely by
 * name at runtime against the real interface `Class` the running IDE hands it, so no unresolved
 * static member reference ever appears in the compiled bytecode for the verifier to flag.
 */
internal val JujutsuHiddenCommitMode: CommitMode = run {
    // Every known member name across both shapes that means "hide everything" - old (2025.2/
    // 2025.3): useCommitToolWindow()/hideLocalChangesTab()/disableDefaultCommitAction(); new
    // (2026.1+): isCommitTwEnabled/isLocalChangesTabHidden/isDefaultCommitActionDisabled.
    val hideAnswers = mapOf(
        "useCommitToolWindow" to false,
        "isCommitTwEnabled" to false,
        "hideLocalChangesTab" to true,
        "isLocalChangesTabHidden" to true,
        "disableDefaultCommitAction" to true,
        "isDefaultCommitActionDisabled" to true
    )
    Proxy.newProxyInstance(
        CommitMode::class.java.classLoader,
        arrayOf(CommitMode::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.getOrNull(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "JujutsuHiddenCommitMode"
            else -> hideAnswers[method.name]
                ?: error("Unexpected CommitMode member requested: ${method.name}")
        }
    } as CommitMode
}
