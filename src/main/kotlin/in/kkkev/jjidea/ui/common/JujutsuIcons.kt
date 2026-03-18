package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object JujutsuIcons {
    @JvmField val Bookmark: Icon = IconLoader.getIcon("/icons/bookmark.svg", javaClass)

    @JvmField val Conflict: Icon = IconLoader.getIcon("/icons/conflict.svg", javaClass)

    @JvmField val Immutable: Icon = IconLoader.getIcon("/icons/immutable.svg", javaClass)

    @JvmField val Mutable: Icon = IconLoader.getIcon("/icons/mutable.svg", javaClass)
}
