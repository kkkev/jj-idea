package `in`.kkkev.jjidea.i18n

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * DevKit's `MessageBundleReferenceContributor` treats any key starting with `plugin.` and ending with
 * `.description` as `plugin.<pluginId>.description` and NPEs on `plugin.description` (no id segment,
 * jj-idea-18ju, GitHub #100). Keys of that shape must carry a non-empty plugin id.
 */
class BundleKeyShapeTest {
    @Test
    fun `plugin description keys carry a plugin id`() {
        val props = Properties()
        javaClass.getResourceAsStream("/messages/JujutsuBundle.properties")!!.reader(Charsets.UTF_8).use(props::load)

        props.stringPropertyNames()
            .filter { it.startsWith("plugin.") && it.endsWith(".description") }
            // "plugin.description" matches both affixes by overlapping on the dot, leaving no id segment
            .filter { it.length <= "plugin.".length + ".description".length - 1 }
            .shouldBeEmpty()
    }
}
