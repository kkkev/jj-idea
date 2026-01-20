package `in`.kkkev.jjidea.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*
import `in`.kkkev.jjidea.JujutsuBundle

/**
 * Settings panel for Jujutsu plugin configuration.
 *
 * Appears under Settings → Version Control → Jujutsu
 */
class JujutsuConfigurable(
    private val project: Project
) : BoundConfigurable(JujutsuBundle.message("settings.title")) {
    private val settings = JujutsuSettings.getInstance(project)

    override fun createPanel(): DialogPanel =
        panel {
            group(JujutsuBundle.message("settings.group.executable")) {
                row(JujutsuBundle.message("settings.jj.path.label")) {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory
                            .createSingleFileOrExecutableAppDescriptor()
                            .withTitle(JujutsuBundle.message("settings.jj.path.chooser.title")),
                        project
                    ).bindText(settings.state::jjExecutablePath)
                        .columns(COLUMNS_LARGE)
                        .comment(JujutsuBundle.message("settings.jj.path.comment"))
                }
            }

            group(JujutsuBundle.message("settings.group.ui")) {
                row {
                    checkBox(JujutsuBundle.message("settings.autorefresh.label"))
                        .bindSelected(settings.state::autoRefreshEnabled)
                        .comment(JujutsuBundle.message("settings.autorefresh.comment"))
                }
                row {
                    checkBox(JujutsuBundle.message("settings.shortformat.label"))
                        .bindSelected(settings.state::showChangeIdsInShortFormat)
                        .comment(JujutsuBundle.message("settings.shortformat.comment"))
                }
                row {
                    checkBox(JujutsuBundle.message("settings.autoopenlog.label"))
                        .bindSelected(settings.state::autoOpenCustomLogTab)
                        .comment(JujutsuBundle.message("settings.autoopenlog.comment"))
                }
            }

            group(JujutsuBundle.message("settings.group.log")) {
                row(JujutsuBundle.message("settings.log.limit.label")) {
                    intTextField(range = 1..1000)
                        .bindIntText(settings.state::logChangeLimit)
                        .columns(COLUMNS_TINY)
                        .comment(JujutsuBundle.message("settings.log.limit.comment"))
                }
            }
        }
}
