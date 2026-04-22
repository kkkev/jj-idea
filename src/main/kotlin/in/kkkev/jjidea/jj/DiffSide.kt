package `in`.kkkev.jjidea.jj

import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.requests.SimpleDiffRequest

/**
 * Represents one side of a diff, including content and title.
 */
interface DiffSide {
    val title: String
    val content: DiffContent

    object Empty : DiffSide {
        override val title = ""
        override val content = EmptyContent()
    }
}

/**
 * Builds a [com.intellij.diff.requests.DiffRequest] from two sides.
 */
fun diffRequest(name: String, left: DiffSide, right: DiffSide) = SimpleDiffRequest(
    name,
    left.content,
    right.content,
    left.title,
    right.title
)
