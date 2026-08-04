package `in`.kkkev.jjidea.ui.components

import com.intellij.icons.AllIcons
import com.intellij.ui.ColorUtil
import com.intellij.ui.scale.JBUIScale
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.common.ScaledIcon
import `in`.kkkev.jjidea.ui.common.accented
import java.awt.Component
import java.awt.Graphics
import javax.swing.Icon
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/**
 * Resolves an [IconSpec.qualified] key (from either icon library) back to a real [Icon] - the single
 * lookup both the outer document and [AtomicHtmlView]'s inner document use to resolve an
 * `<img src='icon:...'/>` element, via the shared `iconViewOrNull` in `AtomicHtmlView.kt`.
 */
object IconResolver {
    val icons = listOf(JujutsuIcons::class, AllIcons::class)
        .flatMap { it.allIcons.toList() }
        .toMap()

    fun resolveIcon(key: String): Icon? {
        val scaleParts = key.split("@", limit = 2)
        val scale = scaleParts.getOrNull(1)?.toFloatOrNull()
        val colorParts = scaleParts[0].split("#", limit = 2)
        val baseIcon = icons[colorParts[0]] ?: return null
        val colored = colorParts.getOrNull(1)?.let { baseIcon.accented(ColorUtil.fromHex(it)) } ?: baseIcon
        return if (scale != null) ScaledIcon(colored, scale) else colored
    }

    private val KClass<*>.allIcons
        get() = java
            .nestMembers
            .flatMap { it.fields.toList() }
            .filter { it.type.isAssignableFrom(Icon::class.java) }
            .associate {
                "${it.declaringClass.name.replace(qualifiedName!!, simpleName!!).replace('$', '.')}.${it.name}" to
                    (it.get(it.declaringClass) as Icon)
            }
}

/**
 * Corrects High-DPI "Double Scaling", reporting the icon's real (unscaled) width/height so
 * [in.kkkev.jjidea.ui.components.IconLeafView] can position it against real font metrics itself -
 * used for every `<img src='icon:...'/>` element, in both the outer document and an
 * [AtomicHtmlView]'s inner document.
 */
internal class ScaleCorrectedIcon(private val source: Icon) : Icon {
    override fun getIconWidth() = (source.iconWidth / JBUIScale.scale(1f)).roundToInt()
    override fun getIconHeight() = (source.iconHeight / JBUIScale.scale(1f)).roundToInt()
    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) = source.paintIcon(c, g, x, y)
}
