package `in`.kkkev.jjidea.ui.common

import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.ui.components.IconResolver
import `in`.kkkev.jjidea.ui.components.icon
import `in`.kkkev.jjidea.ui.log.RepositoryColors
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

object RepositoryIcons {
    private val iconsByRepo = ConcurrentHashMap<JujutsuRepository, Icon>()

    operator fun get(repo: JujutsuRepository) = iconsByRepo[repo] ?: run {
        val fillColor = RepositoryColors.getColor(repo)
        val iconSpec = icon(JujutsuIcons::Repo).copy(fillColor = fillColor)
        IconResolver.resolveIcon(iconSpec.qualified)?.also { iconsByRepo[repo] = it }
    }
}
