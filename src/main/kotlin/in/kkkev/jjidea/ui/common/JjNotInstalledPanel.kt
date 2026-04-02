package `in`.kkkev.jjidea.ui.common

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.JujutsuBundle
import `in`.kkkev.jjidea.jj.JjAvailabilityStatus
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Simple panel shown when jj is not installed or not available.
 * Shows a message and links to settings for configuration.
 */
class JjNotInstalledPanel(private val project: Project, private val status: JjAvailabilityStatus) :
    JPanel(BorderLayout()) {
    init {
        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(20)

            // Title
            val title = JBLabel(getTitle()).apply {
                font = font.deriveFont(font.size * 1.2f)
                alignmentX = CENTER_ALIGNMENT
            }
            add(title)

            add(Box.createVerticalStrut(8))

            // Description
            val description = JBLabel(getDescription()).apply {
                alignmentX = CENTER_ALIGNMENT
            }
            add(description)

            // Configure/settings link (not shown during initial check)
            if (status !is JjAvailabilityStatus.Checking) {
                add(Box.createVerticalStrut(16))
                add(createSettingsLink())
            }
        }

        add(centerPanel, BorderLayout.CENTER)
    }

    private fun getTitle(): String = when (status) {
        is JjAvailabilityStatus.Checking -> JujutsuBundle.message("panel.jj.checking.title")
        is JjAvailabilityStatus.NotFound -> JujutsuBundle.message("panel.jj.notfound.title")
        is JjAvailabilityStatus.VersionTooOld -> JujutsuBundle.message("panel.jj.version.title")
        is JjAvailabilityStatus.InvalidPath -> JujutsuBundle.message("panel.jj.invalid.title")
        is JjAvailabilityStatus.Available -> ""
    }

    private fun getDescription(): String = when (status) {
        is JjAvailabilityStatus.Checking -> JujutsuBundle.message("panel.jj.checking.description")
        is JjAvailabilityStatus.NotFound -> JujutsuBundle.message("panel.jj.notfound.description")
        is JjAvailabilityStatus.VersionTooOld -> JujutsuBundle.message(
            "panel.jj.version.description",
            status.version.toString(),
            status.minimumVersion.toString()
        )

        is JjAvailabilityStatus.InvalidPath -> JujutsuBundle.message(
            "panel.jj.invalid.description",
            status.configuredPath
        )

        is JjAvailabilityStatus.Available -> ""
    }

    private fun createSettingsLink() = HyperlinkLabel(
        JujutsuBundle.message("panel.jj.action.configure")
    ).apply {
        alignmentX = CENTER_ALIGNMENT
        addHyperlinkListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Jujutsu")
        }
    }

    companion object {
        fun create(project: Project, status: JjAvailabilityStatus): JjNotInstalledPanel? =
            if (status is JjAvailabilityStatus.Available) null else JjNotInstalledPanel(project, status)
    }
}
