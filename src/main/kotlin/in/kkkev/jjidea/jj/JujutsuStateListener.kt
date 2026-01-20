package `in`.kkkev.jjidea.jj

import com.intellij.util.messages.Topic

/**
 * Listener interface for Jujutsu VCS state changes.
 * Views can subscribe to receive notifications when the state changes.
 *
 * Uses IntelliJ's MessageBus pattern for loose coupling between model and views.
 */
interface JujutsuStateListener {
    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic.create(
            "Jujutsu State Changes",
            JujutsuStateListener::class.java
        )
    }

    /**
     * Called when the working copy state changes.
     * This includes changes to the description, file changes, or the working copy itself.
     */
    fun workingCopyChanged()

    /**
     * Called when log entries are updated.
     * This happens after VCS operations like new, edit, abandon, etc.
     */
    fun logUpdated()
}
