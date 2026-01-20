package `in`.kkkev.jjidea.vcs.annotate

import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Tests for JujutsuFileAnnotation.
 *
 * Note: Full integration tests for JujutsuFileAnnotation require IntelliJ Platform
 * test fixtures (Project, VirtualFile, etc.) which are not currently set up.
 * This test file documents the expected behavior and verifies compilation.
 *
 * The key fix for issue #34 is that getRevisionsChangesProvider() must return null
 * to prevent EDT slow operations. The default FileAnnotation.getRevisionsChangesProvider()
 * calls createDefaultRevisionsChangesProvider() which invokes:
 *   ProjectLevelVcsManager.getInstance(project).getVcsFor(file)
 *
 * This getVcsFor() call performs slow file system checks (isIgnored(), etc.) on EDT,
 * triggering "Slow operations are prohibited on EDT" errors.
 *
 * By overriding getRevisionsChangesProvider() to return null, we:
 * 1. Prevent the EDT slow operation error
 * 2. Disable the "Show Diff" feature from annotation gutter (acceptable trade-off)
 * 3. Allow annotations to display without performance issues
 *
 * Manual testing verification:
 * 1. Open a file in the editor
 * 2. Right-click → Annotate (or use JujutsuAnnotateAction)
 * 3. Verify annotations display without "Slow operations are prohibited on EDT" errors
 * 4. Verify no performance warnings in IDE log
 */
class JujutsuFileAnnotationTest {
    @Test
    fun `JujutsuFileAnnotation class exists and compiles`() {
        // This test verifies that JujutsuFileAnnotation compiles correctly
        // with the getRevisionsChangesProvider override
        val className = JujutsuFileAnnotation::class.simpleName
        className shouldNotBe null
    }

    @Test
    fun `getRevisionsChangesProvider method is overridden`() {
        // Verify the method exists at compile time
        // The actual null return value prevents EDT slow operations
        val method = JujutsuFileAnnotation::class.java.getDeclaredMethod("getRevisionsChangesProvider")
        method shouldNotBe null
    }
}
