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
import io.kotest.matchers.shouldBe
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
 * This has regressed multiple times since, each time in a spot the width tests of the day
 * didn't cover:
 * - jj-idea-fwea's per-repo diff-base override widened the per-repo "Repository Settings"
 *   group, which the fixture project (no jj repositories) never built — jj-idea-ye1x closed
 *   that blind spot via [stubRepo].
 * - jj-idea-258c's Installation Help content is only built once the group is expanded — its
 *   own [WIDTH_BUDGET] test builds the panel without expanding it, so a dedicated test forces
 *   expansion first (see "fits within budget with Installation Help expanded and gated").
 * - Narrowing *other* rows (jj-idea-258c's `.comment()` calls) silently shrank the panel's
 *   overall baseline enough that a fixed-width row elsewhere (the validation-error label)
 *   became the new bottleneck and exceeded it — "a long validation error does not widen the
 *   panel" catches exactly this class of regression by comparing against the panel's own
 *   width rather than a hardcoded number.
 *
 * [WIDTH_BUDGET] is intentionally kept close to the actual measured width (currently ~531px
 * across every scenario below) rather than the original generous 640px estimate, so a change
 * that quietly adds even a modest amount of width fails a test immediately instead of eroding
 * the safety margin unnoticed until the panel is genuinely too wide again.
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
    fun `Installation Help command labels leave a real rendered gap before their field`() {
        // jj-idea-bslw/258c: "Homebrew:"/"Cargo:" sat uncomfortably close to their command
        // field. A first fix added a Border to the label, which *does* grow its preferredSize
        // (a border-insets assertion here would have passed) but produces **no visible change**:
        // the grid layout treats a component's own border as "visual padding" and shifts the
        // component to absorb it rather than pushing the next cell over (GridImpl.kt computes
        // cell x as `... - visualPaddings.left` and pads the component's own bounds back out by
        // the same amount) — confirmed by actually laying this panel out and finding the gap
        // hadn't moved. So this test lays the panel out for real and measures the actual x-gap
        // between the label and the field, the only way to catch that class of regression.
        val configurable = JujutsuConfigurable(project.get())
        val panel = configurable.createPanel()
        // A collapsed CollapsibleRow's content is measured for preferred size but not actually
        // positioned by layout — expand it so the manual layout pass below assigns it real bounds.
        configurable.expandInstallGroupForTest()

        // Container.validate()/doLayout() silently no-op here: AWT only recurses a validate()
        // into a child Container via validateTree() when that child has a live peer, i.e. is
        // part of a realized, displayable Window — impossible in this headless test JVM (a real
        // JFrame throws HeadlessException here). Each group/collapsibleGroup is its own nested
        // Grid/LayoutManager2, so bypass AWT's peer-gated recursion and call each container's
        // own layoutContainer() directly and top-down instead — pure geometry, no peer needed.
        panel.size = panel.preferredSize
        fun layoutRecursively(c: java.awt.Container) {
            c.layout?.layoutContainer(c)
            c.components.forEach { if (it is java.awt.Container) layoutRecursively(it) }
        }
        layoutRecursively(panel)

        val labels = mutableListOf<javax.swing.JLabel>()
        val fields = mutableListOf<javax.swing.JTextField>()
        fun walk(c: java.awt.Component) {
            when (c) {
                is javax.swing.JLabel -> if (c.text?.endsWith(":") == true) labels += c
                is javax.swing.JTextField -> fields += c
            }
            if (c is java.awt.Container) c.components.forEach(::walk)
        }
        walk(panel)

        val commandRows = InstallMethod.allAvailable.filterNot { it is InstallMethod.Manual }
        commandRows shouldNotBe emptyList<InstallMethod>()
        commandRows.forEach { method ->
            val label = labels.first { it.text == "${method.name}:" }
            // The command field is the JTextField in the same row — LABEL_ALIGNED can vertically
            // offset a label and its field by a few px within the row, so match by y-range
            // overlap rather than exact containment.
            val field = fields.first {
                it.x > label.x && it.y < label.y + label.height && it.y + it.height > label.y
            }

            val gap = field.x - (label.x + label.width)
            gap shouldBeGreaterThan MIN_LABEL_FIELD_GAP
        }
    }

    @Test
    fun `settings panel fits within budget with Installation Help expanded and gated`() {
        // jj-idea-258c: the widest Installation Help can get — every command row plus the
        // gated-feature list, all only laid out once the group is actually expanded.
        val configurable = JujutsuConfigurable(project.get())
        val panel = configurable.createPanel()
        configurable.expandInstallGroupForTest()

        panel.preferredSize.width shouldBeLessThanOrEqual JBUI.scale(WIDTH_BUDGET)
    }

    @Test
    fun `there is no standalone Feature Availability group`() {
        // jj-idea-258c: superseded by folding the gated-feature list directly into Installation
        // Help — regression guard against reintroducing the separate group.
        val panel = JujutsuConfigurable(project.get()).createPanel()

        val texts = mutableListOf<String>()
        fun walk(c: java.awt.Component) {
            (c as? javax.swing.JLabel)?.text?.let { texts += it }
            if (c is java.awt.Container) c.components.forEach(::walk)
        }
        walk(panel)

        texts.none { it.contains("Feature Availability") } shouldBe true
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
         * jj-idea-bwdk originally set this to 640 (a default-size Settings dialog's ~700px minus
         * headroom for insets/scrollbar), but that left ~100px of slack in which the panel could
         * silently grow without any test noticing — which is exactly what happened (see the
         * class doc's regression history). jj-idea-258c tightened it to 560, just above the width
         * it measured locally (~531px) — but that measurement was taken on macOS. The `compat`
         * CI job runs headless on Linux, where AWT substitutes different fonts; the monospace
         * command field (`COLUMNS_SHORT` sized against the font's average char width) and the
         * `.gap(RightGap.COLUMNS)` label spacing both render measurably wider there, landing at
         * 592px in every `compat` matrix leg (2025.1/2025.2/2026.2) — verified by reproducing the
         * `compat` job's exact environment locally (`eclipse-temurin:21-jdk` container). 630
         * keeps the same close-to-actual margin jj-idea-258c intended, sized against the
         * platform CI actually renders on rather than a local macOS measurement.
         */
        private const val WIDTH_BUDGET = 630

        /**
         * Lower bound (raw px, this JVM's default — no scaling in a headless test) for the
         * rendered gap between an Installation Help command label and its field. The DSL's
         * automatic RightGap.SMALL after a row label measured well under this before jj-idea-258c
         * switched to `.gap(RightGap.COLUMNS)`; this is comfortably between the two so the test
         * fails on a regression back to SMALL (or no gap override at all).
         */
        private const val MIN_LABEL_FIELD_GAP = 8

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
