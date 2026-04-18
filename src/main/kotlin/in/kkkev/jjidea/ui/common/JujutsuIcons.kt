package `in`.kkkev.jjidea.ui.common

import com.intellij.ui.JBColor
import java.awt.Color
import javax.swing.Icon

object JujutsuIcons {
    private val GREY = JBColor(Color(0x6c707e), Color(0xced0d6))
    private val CONTRAST_GREY = JBColor(Color(0xebecf0), Color(0x43454a))
    private val RED = JBColor(Color(0xFF0000), Color(0xFF6464))
    private val GREEN = JBColor(Color(0x498C38), Color(0x59A869))

    private fun load(path: String) = SvgIcon.load(path, javaClass)

    private fun strokeIcon(path: String, color: Color) =
        load(path).recolored(mapOf("primary" to ("stroke" to color)))

    private fun strokeFillIcon(path: String, primaryColor: Color, contrastColor: Color? = null) =
        load(path).recolored(mapOf("primary" to ("stroke" to primaryColor), "primary-fill" to ("fill" to primaryColor)))
            .recolored(
                listOfNotNull(
                    "primary" to ("stroke" to primaryColor),
                    "primary-fill" to ("fill" to primaryColor),
                    contrastColor?.let { "contrast" to ("stroke" to it) },
                    contrastColor?.let { "contrast-fill" to ("fill" to it) }
                ).toMap()
            )

    private fun fillIcon(path: String, color: Color) =
        load(path).recolored(mapOf("primary-fill" to ("fill" to color)))

    @JvmField
    val Bookmark: Icon = load("/icons/bookmark.svg").accented(GREY)

    @JvmField
    val BookmarkTracked: Icon = load("/icons/bookmarkTracked.svg").accented(GREY)

    @JvmField
    val Conflict: Icon = fillIcon("/icons/conflict.svg", RED)

    @JvmField
    val Describe: Icon = strokeIcon("/icons/describe.svg", GREY)

    @JvmField
    val Immutable: Icon = strokeFillIcon("/icons/immutable.svg", RED)

    @JvmField
    val Mutable: Icon = strokeFillIcon("/icons/mutable.svg", GREEN)

    @JvmField
    val NewChange: Icon = strokeFillIcon("/icons/newChange.svg", GREY)

    @JvmField
    val Rebase: Icon = strokeFillIcon("/icons/rebase.svg", GREY)

    @JvmField
    val Split: Icon = strokeFillIcon("/icons/split.svg", GREY)

    @JvmField
    val Squash: Icon = strokeFillIcon("/icons/squash.svg", GREY)

    @JvmField
    val Repo: Icon = strokeFillIcon("/icons/repo.svg", GREY, CONTRAST_GREY)
}
