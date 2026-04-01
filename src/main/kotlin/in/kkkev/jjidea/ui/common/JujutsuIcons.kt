package `in`.kkkev.jjidea.ui.common

import com.intellij.ui.JBColor
import java.awt.Color
import javax.swing.Icon

object JujutsuIcons {
    private val GRAY = JBColor(Color(0x6c707e), Color(0xced0d6))
    private val RED = JBColor(Color(0xFF0000), Color(0xFF6464))
    private val GREEN = JBColor(Color(0x498C38), Color(0x59A869))

    private fun load(path: String) = SvgIcon.load(path, javaClass)

    private fun strokeIcon(path: String, color: Color) =
        load(path).recolored(mapOf("primary" to ("stroke" to color)))

    private fun strokeFillIcon(path: String, color: Color) =
        load(path).recolored(mapOf("primary" to ("stroke" to color), "primary-fill" to ("fill" to color)))

    private fun fillIcon(path: String, color: Color) =
        load(path).recolored(mapOf("primary-fill" to ("fill" to color)))

    @JvmField
    val Bookmark: Icon = load("/icons/bookmark.svg").accented(GRAY)

    @JvmField
    val BookmarkTracked: Icon = load("/icons/bookmarkTracked.svg").accented(GRAY)

    @JvmField
    val Conflict: Icon = fillIcon("/icons/conflict.svg", RED)

    @JvmField
    val Describe: Icon = strokeIcon("/icons/describe.svg", GRAY)

    @JvmField
    val Immutable: Icon = strokeFillIcon("/icons/immutable.svg", RED)

    @JvmField
    val Mutable: Icon = strokeFillIcon("/icons/mutable.svg", GREEN)

    @JvmField
    val NewChange: Icon = strokeFillIcon("/icons/newChange.svg", GRAY)

    @JvmField
    val Rebase: Icon = strokeFillIcon("/icons/rebase.svg", GRAY)

    @JvmField
    val Split: Icon = strokeFillIcon("/icons/split.svg", GRAY)

    @JvmField
    val Squash: Icon = strokeFillIcon("/icons/squash.svg", GRAY)

    @JvmField
    val Repo: Icon = strokeFillIcon("/icons/repo.svg", GRAY)
}
