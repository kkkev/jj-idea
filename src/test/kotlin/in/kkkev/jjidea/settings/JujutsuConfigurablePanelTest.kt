package `in`.kkkev.jjidea.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.util.ui.JBUI
import `in`.kkkev.jjidea.jj.InstallMethod
import `in`.kkkev.jjidea.jj.JujutsuRepository
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Regression tests for jj-idea-bwdk: the settings panel's preferred width exceeded the
 * settings dialog's viewport, forcing a horizontal scrollbar. The platform puts a
 * Configurable's component straight into a `JScrollPane` (`ConfigurableCardPanel` in
 * intellij-community), so the scrollbar tracks the panel's *preferred* width, not just
 * clipping — `align(AlignX.FILL)`/`resizableColumn()` only distribute extra space, they
 * don't shrink it.
 *
 * The fixture project has no jj repositories, so this test alone never built the per-repo
 * "Repository Settings" group — jj-idea-fwea's per-repo diff-base override subsequently
 * widened that group past budget without any test noticing. jj-idea-ye1x closes that blind
 * spot via [stubRepo].
 */
@Tag("platform")
@TestApplication
@RunInEdt
class JujutsuConfigurablePanelTest {
    private val project = projectFixture()

    @Test
    fun `settings panel fits within the settings dialog's available width`() {
        val panel = JujutsuConfigurable(project.get()).createPanel()

        panel.preferredSize.width shouldBeLessThanOrEqual JBUI.scale(WIDTH_BUDGET)
    }

    @Test
    fun `settings panel fits within budget with the per-repo group present`() {
        // A lone repo's collapsibleGroup starts expanded (JujutsuConfigurable: `expanded =
        // repos.size == 1`), so its content is actually measured here rather than collapsed
        // away. The name is realistic-length, not a placeholder: JujutsuStateModel's
        // initialisedRepositories uses the directory name verbatim, and directory names in the
        // wild — e.g. a `jj git clone`'d worktree checkout — commonly run 20-30 characters.
        val repos = listOf(stubRepo(project.get(), "/repos/one", "agent-afe4e237c50ae6d6d"))
        val panel = JujutsuConfigurable(project.get(), repos).createPanel()

        panel.preferredSize.width shouldBeLessThanOrEqual JBUI.scale(WIDTH_BUDGET)
    }

    @Test
    fun `settings panel fits within budget with a per-repo custom diffbase override active`() {
        // jj-idea-fwea's per-repo diffbase override is the widest configuration this group can
        // reach: a combo plus a non-empty COLUMNS_MEDIUM revset field, both only laid out once
        // the override is actually on.
        val repo = stubRepo(project.get(), "/repos/one", "agent-afe4e237c50ae6d6d")
        JujutsuSettings.getInstance(project.get()).state.repositoryOverrides["/repos/one"] = RepositoryConfig(
            diffbaseStrategy = DiffbaseStrategy.CUSTOM_REVSET,
            customDiffbaseRevset = "latest(ancestors(@-) & immutable())"
        )

        val panel = JujutsuConfigurable(project.get(), listOf(repo)).createPanel()

        panel.preferredSize.width shouldBeLessThanOrEqual JBUI.scale(WIDTH_BUDGET)
    }

    @Test
    fun `Installation Help command labels have extra right padding before their field`() {
        // jj-idea-bslw: "Homebrew:"/"Cargo:" sat uncomfortably close to their command field —
        // each collapsibleGroup auto-sizes its own label column independent of the (longer) "JJ
        // executable path:" label above, so there's no natural extra room without padding.
        val panel = JujutsuConfigurable(project.get()).createPanel()

        val commandLabels = mutableListOf<javax.swing.JLabel>()
        fun walk(c: java.awt.Component) {
            if (c is javax.swing.JLabel && c.text?.endsWith(":") == true && c.text != "JJ executable path:") {
                commandLabels += c
            }
            if (c is java.awt.Container) c.components.forEach(::walk)
        }
        walk(panel)

        val installMethodLabels = commandLabels.filter { label ->
            InstallMethod.allAvailable.any { it !is InstallMethod.Manual && label.text == "${it.name}:" }
        }
        installMethodLabels shouldNotBe emptyList<javax.swing.JLabel>()
        installMethodLabels.forEach { label ->
            val rightInset = label.border?.getBorderInsets(label)?.right ?: 0
            rightInset shouldBeGreaterThan 0
        }
    }

    @Test
    fun `a long validation error does not widen the panel`() {
        val configurable = JujutsuConfigurable(project.get())
        val panel = configurable.createPanel()
        val widthBefore = panel.preferredSize.width

        val longStderr = "Error: " + "a very long jj error message that keeps going ".repeat(8)
        configurable.showValidationResultForTest(longStderr)

        panel.preferredSize.width shouldBeLessThanOrEqual widthBefore
    }

    companion object {
        /**
         * A default-size Settings dialog leaves roughly 700px to the right of the category
         * tree; this leaves headroom for the page's own 16px insets and a vertical scrollbar.
         */
        private const val WIDTH_BUDGET = 640

        /**
         * A relaxed [JujutsuRepository] double good enough to build the panel's per-repo group:
         * `createPanel()` only reads [JujutsuRepository.directory]'s path and [displayName]
         * synchronously; identity (`repo.config`) is loaded in a background coroutine
         * ([JujutsuConfigurable.loadGlobalIdentity]-style fire-and-forget) that a relaxed mock
         * tolerates without stubbing every call.
         */
        private fun stubRepo(project: Project, path: String, displayName: String): JujutsuRepository {
            val directory = mockk<VirtualFile>(relaxed = true) { every { this@mockk.path } returns path }
            return mockk(relaxed = true) {
                every { this@mockk.project } returns project
                every { this@mockk.directory } returns directory
                every { this@mockk.displayName } returns displayName
            }
        }
    }
}
