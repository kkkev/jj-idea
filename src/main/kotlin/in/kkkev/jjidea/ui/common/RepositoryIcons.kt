package `in`.kkkev.jjidea.ui.common

import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.ui.components.IconResolver
import `in`.kkkev.jjidea.ui.components.icon
import `in`.kkkev.jjidea.ui.log.RepositoryColors
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

object RepositoryIcons {
    // Keyed by directory path, not by JujutsuRepository: this is an application-level object, so
    // a JujutsuRepository key (a data class whose first property is its Project) would pin every
    // project it's ever asked about in memory for the life of the process - LeakHunter catches
    // this as a leaked ProjectImpl at platform-test teardown (jj-idea-o46e). The path is already
    // the effective identity here since RepositoryColors.getColor keys by the same path, so this
    // changes nothing about which repos share an icon.
    private val iconsByPath = ConcurrentHashMap<String, Icon>()

    operator fun get(repo: JujutsuRepository) = iconsByPath[repo.directory.path] ?: run {
        val fillColor = RepositoryColors.getColor(repo)
        val iconSpec = icon(JujutsuIcons::Repo).copy(fillColor = fillColor)
        IconResolver.resolveIcon(iconSpec.qualified)?.also { iconsByPath[repo.directory.path] = it }
    }
}
