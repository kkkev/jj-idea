package `in`.kkkev.jjidea.ui.log

import `in`.kkkev.jjidea.jj.Bookmark
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.LogEntry
import `in`.kkkev.jjidea.jj.Tag
import java.net.URI
import java.net.URLDecoder

sealed interface LogClickTarget {
    val repo: JujutsuRepository
    val entry: LogEntry

    companion object {
        private val REF_URL_PARSER = Regex("^jjref://([^?]+)\\?([^&]+)&kind=([^&]+)&name=(.+)$")

        /** Resolve a `jjref://` [uri] to a [LogClickTarget] using the given [entry]'s ref lists. */
        fun resolve(uri: URI, entry: LogEntry): LogClickTarget? {
            val m = REF_URL_PARSER.matchEntire(uri.toString()) ?: return null
            val kind = m.groupValues[3]
            val name = URLDecoder.decode(m.groupValues[4], "UTF-8")
            return when (kind) {
                "bookmark" -> {
                    val bookmark = entry.bookmarks.find { it.name.name == name } ?: return null
                    BookmarkClick(entry.repo, entry, bookmark)
                }
                "tag" -> {
                    val tag = entry.tags.find { it.name == name } ?: return null
                    TagClick(entry.repo, entry, tag)
                }
                else -> null
            }
        }
    }
}

data class BookmarkClick(
    override val repo: JujutsuRepository,
    override val entry: LogEntry,
    val bookmark: Bookmark
) : LogClickTarget

data class TagClick(
    override val repo: JujutsuRepository,
    override val entry: LogEntry,
    val tag: Tag
) : LogClickTarget
