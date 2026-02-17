package `in`.kkkev.jjidea.ui.components

import com.intellij.icons.AllIcons
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.util.IconUtil
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import javax.swing.Icon
import kotlin.reflect.KClass

/**
 * An HTML pane that can resolve icons from a set of icon libraries, including IDEA's icons
 * [com.intellij.icons.AllIcons].
 */
class IconAwareHtmlPane : JBHtmlPane(
    JBHtmlPaneStyleConfiguration(),
    JBHtmlPaneConfiguration { iconResolver = IconResolver::resolveIcon }
) {
    init {
        isOpaque = false
    }
}

object IconResolver {
    val icons = listOf(
        AllIcons::class,
        JujutsuIcons::class
    ).map { it.allIcons }.fold(emptyMap<String, Icon>()) { a, i -> a + i }

    fun resolveIcon(key: String): Icon? {
        val parts = key.split("#", limit = 2)
        val icon = icons[parts[0]] ?: return null
        val hexColor = parts.getOrNull(1) ?: return icon
        // keepGray preserves white/gray pixels (e.g. exclamation mark in conflict icon)
        return IconUtil.colorize(icon, ColorUtil.fromHex(hexColor), true)
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
