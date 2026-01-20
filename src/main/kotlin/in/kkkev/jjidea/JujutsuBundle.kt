package `in`.kkkev.jjidea

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

/**
 * Resource bundle for localized strings used throughout the Jujutsu plugin.
 *
 * Usage:
 * ```kotlin
 * val message = JujutsuBundle.message("dialog.describe.empty.message")
 * val formatted = JujutsuBundle.message("diff.title.compare", fileName, revision)
 * ```
 */
@NonNls
private const val BUNDLE = "messages.JujutsuBundle"

object JujutsuBundle : DynamicBundle(BUNDLE) {
    /**
     * Get a localized message by key.
     *
     * @param key The message key from JujutsuBundle.properties
     * @param params Optional parameters for message formatting
     * @return The localized message
     */
    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)

    /**
     * Get a localized message by key with a supplier (for lazy loading).
     *
     * @param key The message key from JujutsuBundle.properties
     * @param params Optional parameters for message formatting
     * @return Supplier that provides the localized message
     */
    fun messagePointer(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getLazyMessage(key, *params)
}
