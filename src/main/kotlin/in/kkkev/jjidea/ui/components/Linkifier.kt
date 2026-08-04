package `in`.kkkev.jjidea.ui.components

import com.intellij.openapi.vcs.IssueNavigationConfiguration
import java.net.URI

/** One run of text produced by a [Linkifier] - always just text; [Link] additionally carries a target. */
sealed interface TextRun {
    val text: String
    data class Plain(override val text: String) : TextRun
    data class Link(override val text: String, val target: URI) : TextRun
}

/** This run's link target, or null if it's [TextRun.Plain]. */
val TextRun.target: URI? get() = (this as? TextRun.Link)?.target

/**
 * Splits text into plain and linked [TextRun]s. The single module responsible for "how do we turn
 * text into links" - a [TextCanvas] holds one (see [TextCanvas.linkifier]) instead of every append
 * call taking its own linking config. Style-free by design: the canvas/renderer applies color and
 * hover-underline when it replays a run (see [appendLinkified]), so a chip label or a description can
 * share the same linkifier without it needing to know their ambient styling.
 */
fun interface Linkifier {
    fun linkify(text: String): List<TextRun>

    /** No linkification - every call returns [text] as one [TextRun.Plain]. */
    object None : Linkifier {
        override fun linkify(text: String) = listOf(TextRun.Plain(text))
    }
}

/**
 * Linkifies issue-tracker references (e.g. `JIRA-123`) and bare URLs found by [config] (jj-idea-10fo).
 * A malformed link target (an invalid URI) falls back to a plain run for that piece rather than
 * throwing.
 */
class IssueLinkifier(private val config: IssueNavigationConfiguration) : Linkifier {
    override fun linkify(text: String): List<TextRun> {
        val matches = config.findIssueLinks(text)
        if (matches.isEmpty()) return listOf(TextRun.Plain(text))
        val runs = mutableListOf<TextRun>()
        IssueNavigationConfiguration.processTextWithLinks(
            text,
            matches,
            { plain -> runs += TextRun.Plain(plain) },
            { linkText, target ->
                val uri = runCatching { URI(target) }.getOrNull()
                runs += if (uri != null) TextRun.Link(linkText, uri) else TextRun.Plain(linkText)
            }
        )
        return runs
    }
}
