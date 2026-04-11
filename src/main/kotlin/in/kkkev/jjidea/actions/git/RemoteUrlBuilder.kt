package `in`.kkkev.jjidea.actions.git

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.util.IconLoader
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.GitRemote
import javax.swing.Icon

internal enum class RemoteKind(val label: String, val icon: Icon) {
    GITHUB("GitHub", AllIcons.Vcs.Vendors.Github),
    GITLAB("GitLab", IconLoader.getIcon("/icons/gitlab.svg", RemoteKind::class.java))
}

/** A git remote classified as a known hosting service, without commit-specific data. */
internal data class ClassifiedRemote(val name: String, val base: String, val kind: RemoteKind)

/** A git remote classified as a known hosting service, enriched with data for a specific commit. */
internal data class RecognizedRemote(
    val name: String,
    val kind: RemoteKind,
    val base: String,
    val commitUrl: String,
    val isPushed: Boolean
)

/**
 * Applies 0/1/2+ visibility logic to this action group based on [count] available remotes.
 * - 0: hidden
 * - 1: visible and transparent (child shown inline)
 * - 2+: visible as popup with [popupText]
 */
internal fun DefaultActionGroup.applyRemoteVisibility(
    e: AnActionEvent,
    count: Int,
    popupText: String = JujutsuBundle.message("log.action.open.on.remote")
) = when (count) {
    0 -> e.presentation.isVisible = false
    1 -> {
        e.presentation.isVisible = true
        isPopup = false
    }
    else -> {
        e.presentation.isVisible = true
        isPopup = true
        e.presentation.text = popupText
    }
}

internal object RemoteUrlBuilder {
    /**
     * Builds [RecognizedRemote] entries for each recognized remote (GitHub/GitLab) in [remotes].
     * [isPushed] is passed through directly — callers should use [LogEntry.immutable] as the heuristic.
     */
    fun recognizedRemotes(remotes: List<GitRemote>, commitHash: String, isPushed: Boolean): List<RecognizedRemote> =
        remotes.mapNotNull { remote ->
            val (base, kind) = parseBaseUrl(remote.url) ?: return@mapNotNull null
            val commitPath = if (kind == RemoteKind.GITLAB) "/-/commit/$commitHash" else "/commit/$commitHash"
            RecognizedRemote(remote.name, kind, base, "$base$commitPath", isPushed)
        }

    /**
     * Classifies [remotes] by base URL and kind without commit-specific data.
     * Use this when the commit hash is not yet known (e.g. the editor "open latest pushed" action).
     */
    fun classifiedRemotes(remotes: List<GitRemote>): List<ClassifiedRemote> =
        remotes.mapNotNull { remote ->
            val (base, kind) = parseBaseUrl(remote.url) ?: return@mapNotNull null
            ClassifiedRemote(remote.name, base, kind)
        }

    fun fileUrl(base: String, kind: RemoteKind, commitHash: String, repoRelativePath: String): String {
        val blobPath = if (kind == RemoteKind.GITLAB) "/-/blob" else "/blob"
        return "$base$blobPath/$commitHash/$repoRelativePath"
    }

    internal fun parseBaseUrl(remoteUrl: String): Pair<String, RemoteKind>? {
        // SSH: git@github.com:user/repo.git
        val sshMatch = Regex("""^git@([^:]+):(.+?)(?:\.git)?$""").matchEntire(remoteUrl)
        if (sshMatch != null) {
            val host = sshMatch.groupValues[1]
            val path = sshMatch.groupValues[2]
            val kind = hostKind(host) ?: return null
            return "https://$host/$path" to kind
        }

        // HTTPS: https://github.com/user/repo.git
        val httpsMatch = Regex("""^https?://([^/]+)/(.+?)(?:\.git)?$""").matchEntire(remoteUrl)
        if (httpsMatch != null) {
            val host = httpsMatch.groupValues[1]
            val path = httpsMatch.groupValues[2]
            val kind = hostKind(host) ?: return null
            return "https://$host/$path" to kind
        }

        return null
    }

    private fun hostKind(host: String) = when (host) {
        "github.com" -> RemoteKind.GITHUB
        "gitlab.com" -> RemoteKind.GITLAB
        else -> null
    }
}
