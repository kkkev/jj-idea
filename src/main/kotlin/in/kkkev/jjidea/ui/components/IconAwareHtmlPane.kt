package `in`.kkkev.jjidea.ui.components

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
import com.intellij.ui.scale.JBUIScale
import com.intellij.vcsUtil.VcsUtil
import `in`.kkkev.jjidea.jj.ChangeId
import `in`.kkkev.jjidea.jj.invalidate
import `in`.kkkev.jjidea.ui.common.JujutsuIcons
import `in`.kkkev.jjidea.ui.common.ScaledIcon
import `in`.kkkev.jjidea.ui.common.accented
import `in`.kkkev.jjidea.vcs.jujutsuRepositoryFor
import java.awt.Component
import java.awt.Graphics
import java.util.regex.Pattern
import javax.swing.Icon
import javax.swing.event.HyperlinkEvent
import kotlin.math.roundToInt
import kotlin.reflect.KClass

private val CHANGE_ID_URL_PARSER = Pattern.compile("^jjc://([^?]+)\\?(.+)$")

/**
 * An HTML pane that can resolve icons from a set of icon libraries, including IDEA's icons
 * [com.intellij.icons.AllIcons].
 */
class IconAwareHtmlPane(private val project: Project) : JBHtmlPane(
    JBHtmlPaneStyleConfiguration(),
    JBHtmlPaneConfiguration { iconResolver = { IconResolver.resolveIcon(it)?.let(::HtmlIcon) } }
) {
    init {
        isOpaque = false
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                with(CHANGE_ID_URL_PARSER.matcher(e.description)) {
                    if (matches()) {
                        project.jujutsuRepositoryFor(VcsUtil.getFilePath(this.group(1), true))
                            .invalidate(ChangeId(this.group(2)))
                    }
                }
            }
        }
    }
}

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
 * Wraps icons for JBHtmlPane to fix High-DPI "Double Scaling" and baseline alignment.
 */
private class HtmlIcon(private val source: Icon) : Icon {
    // Report 0 height. This is the only way to stop JBIconView from adding whitespace above text lines with icons.
    override fun getIconWidth() = (source.iconWidth / JBUIScale.scale(1f)).roundToInt()
    override fun getIconHeight() = 0

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        source.paintIcon(c, g, x, y - source.iconHeight + g.fontMetrics.descent)
    }
}
