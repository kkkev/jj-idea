package `in`.kkkev.jjidea.ui.common

import `in`.kkkev.jjidea.JujutsuBundle
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** [JujutsuOtherRepositoriesNode]'s own properties. Needs no `Project`, so this is a plain unit test. */
class JujutsuOtherRepositoriesNodeTest {
    @Test
    fun `collapsed by default`() {
        JujutsuOtherRepositoriesNode().shouldExpandByDefault() shouldBe false
    }

    @Test
    fun `sorts after ordinary top-level content (directory, file, and change nodes)`() {
        // ChangesBrowserNode.java's protected constants aren't accessible from here (this test
        // deliberately isn't a ChangesBrowserNode subclass, to stay a plain, Project-free unit
        // test), so this pins the actual value rather than referencing UNVERSIONED_SORT_WEIGHT
        // symbolically: 9, versus DIRECTORY_PATH_SORT_WEIGHT=5..CHANGE_SORT_WEIGHT=7 for the
        // ordinary top-level nodes this must sort after (see this class's KDoc/source comment).
        JujutsuOtherRepositoriesNode().sortWeight shouldBe 9
    }

    @Test
    fun `labeled Other Repositories`() {
        JujutsuOtherRepositoriesNode().textPresentation shouldBe JujutsuBundle.message("changes.node.otherrepos")
    }
}
