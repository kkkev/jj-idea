package `in`.kkkev.jjidea.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.*

/**
 * Settings panel for Jujutsu plugin configuration.
 *
 * Appears under Settings → Version Control → Jujutsu
 */
class JujutsuConfigurable(private val project: Project) : BoundConfigurable("Jujutsu") {
    private val settings = JujutsuSettings.getInstance(project)

    override fun createPanel(): DialogPanel = panel {
        group("Executable") {
            row("JJ executable path:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFileOrExecutableAppDescriptor()
                        .withTitle("Select JJ Executable"),
                    project
                ).bindText(settings.state::jjExecutablePath)
                    .columns(COLUMNS_LARGE)
                    .comment("Path to the jj executable (default: 'jj' from PATH)")
            }
        }

        group("UI Preferences") {
            row {
                checkBox("Enable auto-refresh")
                    .bindSelected(settings.state::autoRefreshEnabled)
                    .comment("Automatically refresh working copy status when files change")
            }
            row {
                checkBox("Show change IDs in short format")
                    .bindSelected(settings.state::showChangeIdsInShortFormat)
                    .comment("Display shortened change IDs (e.g., 'qpvuntsm' instead of full format)")
            }
        }

        group("Log Settings") {
            row("Number of changes to show:") {
                intTextField(range = 1..1000)
                    .bindIntText(settings.state::logChangeLimit)
                    .columns(COLUMNS_TINY)
                    .comment("Default number of changes to load in log view")
            }
        }
    }
}
