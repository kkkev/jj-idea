# IntelliJ IDEA VCS Integration API Reference

Comprehensive reference for IntelliJ Platform VCS integration points, interfaces, and extension points.

**Last Updated**: 2025-12-02

---

## Table of Contents

1. [Core VCS Architecture](#core-vcs-architecture)
2. [Provider Interfaces](#provider-interfaces)
3. [Environment Interfaces](#environment-interfaces)
4. [Tool Window Integration](#tool-window-integration)
5. [File Status and Colors](#file-status-and-colors)
6. [Action System Integration](#action-system-integration)
7. [Change Tracking and Listeners](#change-tracking-and-listeners)
8. [Diff and Merge UI](#diff-and-merge-ui)
9. [Root Detection and Configuration](#root-detection-and-configuration)
10. [Background Tasks and Threading](#background-tasks-and-threading)
11. [Notifications and User Feedback](#notifications-and-user-feedback)
12. [Configuration and Settings](#configuration-and-settings)
13. [Extension Points Summary](#extension-points-summary)
14. [Best Practices and Patterns](#best-practices-and-patterns)

---

## Core VCS Architecture

### AbstractVcs - The Main Entry Point

`com.intellij.openapi.vcs.AbstractVcs`

The foundation of any VCS plugin. Single instance per project that provides access to all VCS functionality.

**Registration in plugin.xml:**
```xml
<extensions defaultExtensionNs="com.intellij">
    <vcs name="jj" vcsClass="in.kkkev.jjidea.JujutsuVcs" />
</extensions>
```

**Key Methods:**

#### Identification
| Method | Purpose |
|--------|---------|
| `getName()` | VCS identifier (e.g., "jj") |
| `getDisplayName()` | Localized UI name |
| `getType()` | VcsType: Local, Distributed, or Changeset |
| `getProject()` | Associated Project instance |

#### Provider Methods
| Method | Returns | Purpose |
|--------|---------|---------|
| `getChangeProvider()` | ChangeProvider | Track working copy changes |
| `getDiffProvider()` | DiffProvider | Provide diff content |
| `getVcsHistoryProvider()` | VcsHistoryProvider | File history |
| `getAnnotationProvider()` | AnnotationProvider | Blame/annotate |
| `getMergeProvider()` | MergeProvider | Conflict resolution |
| `getCommittedChangesProvider()` | CommittedChangesProvider | Committed history |

#### Environment Methods
| Method | Returns | Purpose |
|--------|---------|---------|
| `getCheckinEnvironment()` | CheckinEnvironment | Commit operations |
| `getRollbackEnvironment()` | RollbackEnvironment | Revert operations |
| `getUpdateEnvironment()` | UpdateEnvironment | Update/sync operations |

#### Lifecycle Methods
| Method | Purpose | When Called |
|--------|---------|-------------|
| `start()` | Initialize VCS | VCS becomes active |
| `activate()` | Activate integration | VCS mapping configured |
| `deactivate()` | Deactivate integration | VCS mapping removed |
| `shutdown()` | Cleanup | IDE shuts down |

**Key Point**: Install/remove listeners in `activate()`/`deactivate()`, not constructor.

---

## Provider Interfaces

### ChangeProvider - Track Working Copy Changes

`com.intellij.openapi.vcs.changes.ChangeProvider`

**Purpose**: Detect modifications in working copy and report to IDE.

**Main Method:**
```kotlin
fun getChanges(
    dirtyScope: VcsDirtyScope,
    builder: ChangelistBuilder,
    progress: ProgressIndicator,
    changeListListener: ChangeListListener
)
```

**Threading**: Runs on background thread (not EDT).

**What to Report via ChangelistBuilder:**
```kotlin
builder.processChange(change)                    // Modified/Added/Deleted/Renamed
builder.processUnversionedFile(file)             // Not under VCS
builder.processLocallyDeletedFile(file)          // Deleted from working copy
builder.processIgnoredFile(file)                 // Ignored by VCS
builder.processSwitchedFile(file, branch)        // On different branch
builder.processModifiedFile(file, revision)      // Modified at revision
```

**Related: VcsDirtyScopeManager**
```kotlin
VcsDirtyScopeManager.getInstance(project).fileDirty(filePath)      // Mark file dirty
VcsDirtyScopeManager.getInstance(project).dirDirtyRecursively(dir) // Mark directory dirty
```

Marking files dirty triggers `getChanges()` on background thread.

**Example:**
```kotlin
class JujutsuChangeProvider(private val vcs: JujutsuVcs) : ChangeProvider {
    override fun getChanges(dirtyScope: VcsDirtyScope, builder: ChangelistBuilder,
                           progress: ProgressIndicator, changeListListener: ChangeListListener) {
        val statusOutput = vcs.commandExecutor.status(vcs.root)

        statusOutput.changes.forEach { change ->
            builder.processChange(change)
        }

        statusOutput.unversioned.forEach { file ->
            builder.processUnversionedFile(file)
        }
    }
}
```

---

### DiffProvider - Provide File Diffs

`com.intellij.openapi.vcs.diff.DiffProvider`

**Purpose**: Supply diff content for showing changes between revisions.

**Key Methods:**
```kotlin
fun getCurrentRevision(file: VirtualFile): ContentRevision?      // Working copy
fun getRevision(file: VirtualFile, revisionNumber: VcsRevisionNumber): ContentRevision?
```

**Related: ContentRevision Interface**
```kotlin
interface ContentRevision {
    fun getContent(): String?                    // File content as text
    fun getContentAsBytes(): ByteArray?          // Binary content
    fun getFile(): FilePath                      // File path
    fun getRevisionNumber(): VcsRevisionNumber   // Revision identifier
}
```

**Threading**:
- Methods can be called from EDT
- Content loading should happen off-EDT (background tasks)
- UI updates must be on EDT

**Example:**
```kotlin
class JujutsuDiffProvider(private val project: Project, private val vcs: JujutsuVcs) : DiffProvider {
    override fun getCurrentRevision(file: VirtualFile): ContentRevision? {
        return CurrentContentRevision(file.toFilePath())
    }

    override fun getRevision(file: VirtualFile, revisionNumber: VcsRevisionNumber): ContentRevision? {
        return vcs.createRevision(file.toFilePath(), revisionNumber.asString())
    }
}
```

---

### VcsHistoryProvider - File History

`com.intellij.openapi.vcs.history.VcsHistoryProvider`

**Purpose**: Provide file history and support annotations.

**Key Method:**
```kotlin
@RequiresBackgroundThread
fun createSessionFor(filePath: FilePath): VcsHistorySession?
```

**Threading**: MUST run on background thread, NOT EDT.

**VcsHistorySession Interface:**
```kotlin
interface VcsHistorySession {
    fun getRevisionList(): List<VcsFileRevision>
    fun getCurrentRevisionNumber(): VcsRevisionNumber?
    fun isCurrentRevision(revisionNumber: VcsRevisionNumber): Boolean
    fun shouldBeRefreshed(): Boolean
}
```

**VcsFileRevision Interface:**
```kotlin
interface VcsFileRevision {
    fun getRevisionNumber(): VcsRevisionNumber
    fun getRevisionDate(): Date
    fun getAuthor(): String?
    fun getCommitMessage(): String?
    fun getContent(): ByteArray?
    fun loadContent(): Unit  // Load content on background thread
}
```

**Example:**
```kotlin
class JujutsuHistoryProvider(private val vcs: JujutsuVcs) : VcsHistoryProvider {
    override fun createSessionFor(filePath: FilePath): VcsHistorySession? {
        // Execute on background thread
        val logOutput = vcs.commandExecutor.log(filePath.path)
        val revisions = parseRevisions(logOutput)
        return JujutsuHistorySession(revisions, filePath)
    }
}
```

---

### AnnotationProvider - Blame/Annotate

`com.intellij.openapi.vcs.annotate.AnnotationProvider`

**Purpose**: Provide per-line blame/annotation information.

**Key Method:**
```kotlin
fun annotate(file: VirtualFile): FileAnnotation?
fun annotate(file: VirtualFile, revision: VcsFileRevision): FileAnnotation?
```

**FileAnnotation Interface:**
```kotlin
abstract class FileAnnotation {
    abstract fun getLineRevisionNumber(lineNumber: Int): VcsRevisionNumber?
    abstract fun getLineDate(lineNumber: Int): Date?
    abstract fun getLineAuthor(lineNumber: Int): String?
    abstract fun getToolTip(lineNumber: Int): String?
}
```

**Use Case**: Show which commit modified each line of code in editor gutter.

**Example:**
```kotlin
class JujutsuAnnotationProvider(private val vcs: JujutsuVcs) : AnnotationProvider {
    override fun annotate(file: VirtualFile): FileAnnotation? {
        val annotateOutput = vcs.commandExecutor.annotate(file.path)
        return JujutsuFileAnnotation(annotateOutput, vcs)
    }
}
```

---

### MergeProvider - Conflict Resolution

`com.intellij.openapi.vcs.merge.MergeProvider`

**Purpose**: Handle merge conflicts and resolution.

**Key Methods:**
```kotlin
fun loadRevisions(file: VirtualFile): MergeData
fun conflictResolvedForFile(file: VirtualFile)
```

**MergeData Class:**
```kotlin
class MergeData {
    var CURRENT: ByteArray     // "Ours" (current version)
    var LAST: ByteArray        // "Theirs" (incoming version)
    var ORIGINAL: ByteArray    // Base (common ancestor)
}
```

**Example:**
```kotlin
class JujutsuMergeProvider(private val vcs: JujutsuVcs) : MergeProvider {
    override fun loadRevisions(file: VirtualFile): MergeData {
        val content = file.readText()
        // Parse conflict markers
        val (ours, theirs, base) = parseConflictMarkers(content)
        return MergeData().apply {
            CURRENT = ours.toByteArray()
            LAST = theirs.toByteArray()
            ORIGINAL = base.toByteArray()
        }
    }

    override fun conflictResolvedForFile(file: VirtualFile) {
        // Mark as resolved (may involve VCS command)
    }
}
```

---

## Environment Interfaces

### CheckinEnvironment - Commit Operations

`com.intellij.openapi.vcs.checkin.CheckinEnvironment`

**Purpose**: Implement commit/checkin operations.

**Key Method:**
```kotlin
fun commit(changes: List<Change>,
           preparedComment: String): List<VcsException>
```

**Note**: Returning non-null from `AbstractVcs.getCheckinEnvironment()` causes IntelliJ to automatically create a "Commit" tool window. Return `null` to disable this behavior and use custom UI instead.

**Example:**
```kotlin
class JujutsuCheckinEnvironment(private val vcs: JujutsuVcs) : CheckinEnvironment {
    override fun commit(changes: List<Change>, preparedComment: String): List<VcsException> {
        val result = vcs.commandExecutor.describe(preparedComment)
        return if (result.isSuccess) emptyList()
               else listOf(VcsException(result.stderr))
    }
}
```

---

### RollbackEnvironment - Revert Operations

`com.intellij.openapi.vcs.rollback.RollbackEnvironment`

**Purpose**: Implement revert/discard operations.

**Key Method:**
```kotlin
fun rollbackChanges(changes: List<Change>,
                    vcsExceptions: MutableList<VcsException>,
                    listener: RollbackProgressListener)
```

---

### UpdateEnvironment - Update/Sync Operations

`com.intellij.openapi.vcs.update.UpdateEnvironment`

**Purpose**: Implement update/sync operations (pull, fetch, merge).

**Note**: Less relevant for distributed VCS like Jujutsu. Consider using separate actions for `jj git fetch` instead.

---

## Tool Window Integration

### ToolWindowFactory - Create Tool Windows

`com.intellij.openapi.wm.ToolWindowFactory`

**Purpose**: Create custom tool windows for VCS UI.

**Registration in plugin.xml:**
```xml
<extensions defaultExtensionNs="com.intellij">
    <toolWindow id="Jujutsu"
                anchor="left"
                icon="AllIcons.Vcs.Branch"
                factoryClass="in.kkkev.jjidea.ui.JujutsuToolWindowFactory"
                canCloseContents="false" />
</extensions>
```

**Attributes:**
- `id` (required) - Tool window identifier
- `anchor` - Position: "left", "right", "bottom", "top"
- `secondary` - Primary (false) or secondary (true) stripe
- `icon` - Button icon (use AllIcons.*)
- `factoryClass` (required) - ToolWindowFactory implementation
- `canCloseContents` - Allow closing tabs (default: true)
- `defaultState` - Default visibility state

**Implementation:**
```kotlin
class JujutsuToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        // Create first tab
        val panel1 = JujutsuToolWindowPanel(project)
        val content1 = contentFactory.createContent(panel1.getContent(), "Changes", false)
        toolWindow.contentManager.addContent(content1)

        // Create second tab
        val panel2 = JujutsuLogPanel(project)
        val content2 = contentFactory.createContent(panel2.getContent(), "Log", false)
        toolWindow.contentManager.addContent(content2)
    }

    override fun shouldBeAvailable(project: Project): Boolean {
        // Only show if project uses Jujutsu
        return ProjectLevelVcsManager.getInstance(project)
            .allVcsRoots.any { it.vcs?.name == "Jujutsu" }
    }
}
```

**ToolWindow API:**
```kotlin
toolWindow.contentManager.addContent(content)      // Add tab
toolWindow.contentManager.removeContent(content)   // Remove tab
toolWindow.activate(runnable)                      // Show and focus
toolWindow.hide(runnable)                          // Hide
toolWindow.setIcon(icon)                           // Update icon
toolWindow.setTitle(title)                         // Update title
```

**Threading**: `createToolWindowContent()` is called on EDT.

---

## File Status and Colors

### FileStatus API

`com.intellij.openapi.vcs.FileStatus`

**Purpose**: Define file status colors and indicators.

**Predefined Statuses:**
```kotlin
FileStatus.MODIFIED       // Blue
FileStatus.ADDED          // Green
FileStatus.DELETED        // Gray
FileStatus.RENAMED        // Purple
FileStatus.MERGED         // Cyan
FileStatus.CONFLICTED     // Red
FileStatus.OBSOLETE       // Gray
FileStatus.NOT_CHANGED    // Default
FileStatus.UNKNOWN        // Red
FileStatus.IGNORED        // Gray
```

**Creating Custom Status:**
```kotlin
val customStatus = FileStatusFactory.getInstance()
    .createFileStatus("CUSTOM_ID", "Custom Status", Color.ORANGE)
```

**Usage**: Report in `ChangelistBuilder.processChange()`. IDE uses for file coloring in project tree and changes window.

---

## Action System Integration

### VCS Actions

**Purpose**: Add VCS-related actions to menus and toolbars.

**Registration in plugin.xml:**
```xml
<actions>
    <action id="Jujutsu.Describe"
            class="in.kkkev.jjidea.actions.JujutsuDescribeAction"
            text="Describe Change"
            description="Set description for current change">
        <add-to-group group-id="VcsGroup" anchor="last" />
    </action>
</actions>
```

**Standard Action Locations:**
- VCS menu in menu bar
- Context menu (right-click in project tree)
- Changes tool window toolbar
- Editor context menu

**Performance Note**: `update()` called frequently (~2x per second if user active). Must be fast - no expensive operations.

---

## Change Tracking and Listeners

### ChangeListManager

`com.intellij.openapi.vcs.changes.ChangeListManager`

**Purpose**: Central tracker for all changes in working copy.

**Key Methods:**
```kotlin
fun getChange(file: VirtualFile): Change?
fun getChanges(): List<Change>
fun ensureUpToDate(changelistRefresh: Boolean)
```

**Listening to Changes:**
```kotlin
project.messageBus.connect(disposable)
    .subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
        override fun changeListChanged(changeList: ChangeList) {
            // Handle change list update
        }
    })
```

### File Status Listener

```kotlin
project.messageBus.connect(disposable)
    .subscribe(FileStatusListener.TOPIC, object : FileStatusListener {
        override fun fileStatusChanged(file: VirtualFile) {
            // Handle status change for specific file
        }

        override fun fileStatusesChanged() {
            // Handle bulk status changes
        }
    })
```

### Virtual File Listener (for auto-refresh)

```kotlin
class MyVfsListener : VirtualFileListener {
    override fun contentsChanged(event: VirtualFileEvent) {
        VcsDirtyScopeManager.getInstance(project).fileDirty(event.file)
    }

    override fun fileCreated(event: VirtualFileEvent) {
        VcsDirtyScopeManager.getInstance(project).fileDirty(event.file)
    }

    override fun fileDeleted(event: VirtualFileEvent) {
        VcsDirtyScopeManager.getInstance(project).fileDirty(event.file)
    }
}

// Register in activate()
override fun activate() {
    VirtualFileManager.getInstance().addVirtualFileListener(listener, this)
}
```

---

## Diff and Merge UI

### SimpleDiffRequest - Show Diff

**Purpose**: Display diff between two file revisions.

**Example:**
```kotlin
private fun showDiff(change: Change) {
    ApplicationManager.getApplication().executeOnPooledThread {
        // Load content off EDT
        val beforeContent = change.beforeRevision?.content ?: ""
        val afterFile = LocalFileSystem.getInstance()
            .findFileByPath(change.afterRevision?.file?.path)

        ApplicationManager.getApplication().invokeLater {
            // Create UI on EDT
            val contentFactory = DiffContentFactory.getInstance()
            val content1 = contentFactory.create(project, beforeContent, "Before")
            val content2 = if (afterFile?.exists() == true) {
                contentFactory.create(project, afterFile)  // Editable
            } else {
                contentFactory.create(project, "", "After")
            }

            val diffRequest = SimpleDiffRequest(
                "Diff: ${change.filePath.name}",
                content1,
                content2,
                "Before",
                "After"
            )

            DiffManager.getInstance().showDiff(project, diffRequest)
        }
    }
}
```

**Content Types:**
- String-based: Read-only
- VirtualFile-based: Editable (working copy)
- DocumentContent: From Document (for editors)

---

## Root Detection and Configuration

### VcsRootChecker

`com.intellij.openapi.vcs.VcsRootChecker`

**Purpose**: Check if a directory is a valid VCS root.

**Extension Point**: `com.intellij.vcs.vcsRootChecker`

**Key Methods:**
```kotlin
fun isRoot(path: String): Boolean              // Is this a VCS root?
fun isVcsDir(dirName: String): Boolean         // Looks like VCS dir (e.g., .jj)?
fun getSupportedVcs(): VcsKey                  // Which VCS?
```

**Example:**
```kotlin
class JujutsuRootChecker : VcsRootChecker {
    override fun isRoot(path: String): Boolean {
        return File(path).resolve(".jj").exists()
    }

    override fun isVcsDir(dirName: String): Boolean {
        return dirName == ".jj"
    }

    override fun getSupportedVcs() = JujutsuVcs.getKey()
}
```

**Registration:**
```xml
<extensions defaultExtensionNs="com.intellij">
    <vcsRootChecker implementation="in.kkkev.jjidea.JujutsuRootChecker" />
</extensions>
```

### ProjectLevelVcsManager

`com.intellij.openapi.vcs.ProjectLevelVcsManager`

**Purpose**: Manage VCS root mappings for project.

**Key Methods:**
```kotlin
fun setDirectoryMappings(mappings: List<VcsDirectoryMapping>)
fun getRootsUnderVcs(vcs: AbstractVcs): Array<VirtualFile>
fun getVcsRootFor(file: VirtualFile): VirtualFile?
```

---

## Background Tasks and Threading

### ProgressManager - Background Operations

**Purpose**: Run long operations off EDT with progress indication.

**Pattern:**
```kotlin
ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Task Title") {
    override fun run(indicator: ProgressIndicator) {
        indicator.text = "Processing..."
        indicator.isIndeterminate = false
        indicator.fraction = 0.0

        // Long-running operation
        val result = vcs.commandExecutor.status(vcs.root)

        indicator.fraction = 0.5
        indicator.text = "Parsing results..."

        // Check for cancellation
        indicator.checkCanceled()

        // Update UI on EDT
        ApplicationManager.getApplication().invokeLater {
            updateUI(result)
        }
    }

    override fun onSuccess() {
        // Called on EDT
    }

    override fun onCancel() {
        // Called on EDT
    }
})
```

### Threading Rules

**MUST run on background thread:**
- VCS command execution
- File I/O operations
- Network operations
- `ChangeProvider.getChanges()`
- `VcsHistoryProvider.createSessionFor()`

**MUST run on EDT:**
- UI creation and updates
- Swing component manipulation
- Tool window content changes

**Checking Current Thread:**
```kotlin
ApplicationManager.getApplication().isDispatchThread  // true if on EDT
```

**Running on EDT:**
```kotlin
ApplicationManager.getApplication().invokeLater {
    // Update UI
}
```

**Running on Background Thread:**
```kotlin
ApplicationManager.getApplication().executeOnPooledThread {
    // Long-running work
}
```

---

## Notifications and User Feedback

### NotificationGroup

**Purpose**: Display user notifications.

**Registration in plugin.xml:**
```xml
<extensions defaultExtensionNs="com.intellij">
    <notificationGroup id="Jujutsu Notifications"
                       displayType="BALLOON" />
</extensions>
```

**Display Types:**
- `BALLOON` - Floating notification (most common)
- `BANNER` - Top-of-editor banner
- `TOOL_WINDOW` - Dedicated tool window tab
- `NONE` - Only in notifications history

**Usage:**
```kotlin
NotificationGroupManager.getInstance()
    .getNotificationGroup("Jujutsu Notifications")
    .createNotification("Title", "Message", NotificationType.INFORMATION)
    .notify(project)
```

**Notification Types:**
- `NotificationType.INFORMATION` - Blue, informational
- `NotificationType.WARNING` - Yellow, warning
- `NotificationType.ERROR` - Red, error

---

## Configuration and Settings

### PersistentStateComponent - Store Settings

**Purpose**: Persist VCS-specific configuration.

**Example:**
```kotlin
@State(
    name = "JujutsuConfiguration",
    storages = [Storage("jujutsu.xml")]
)
class JujutsuConfiguration : PersistentStateComponent<JujutsuConfigState> {
    private var state = JujutsuConfigState()

    override fun getState(): JujutsuConfigState = state

    override fun loadState(state: JujutsuConfigState) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): JujutsuConfiguration {
            return project.getService(JujutsuConfiguration::class.java)
        }
    }
}

data class JujutsuConfigState(
    var jjExecutablePath: String = "jj",
    var autoRefresh: Boolean = true,
    var showChangeIds: Boolean = true
)
```

**Registration:**
```xml
<extensions defaultExtensionNs="com.intellij">
    <projectService serviceImplementation="in.kkkev.jjidea.settings.JujutsuConfiguration" />
</extensions>
```

### Configurable - Settings UI

**Purpose**: Provide UI for settings panel.

**Example:**
```kotlin
class JujutsuConfigurable(private val project: Project) : Configurable {
    private var panel: JujutsuSettingsPanel? = null

    override fun getDisplayName() = "Jujutsu"

    override fun createComponent(): JComponent {
        panel = JujutsuSettingsPanel(project)
        return panel!!
    }

    override fun isModified(): Boolean {
        return panel?.isModified() ?: false
    }

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }
}
```

**Registration:**
```xml
<extensions defaultExtensionNs="com.intellij">
    <projectConfigurable
        id="vcs.Jujutsu"
        displayName="Jujutsu"
        instance="in.kkkev.jjidea.settings.JujutsuConfigurable"
        parentId="project.propVCSSupport.Mappings" />
</extensions>
```

---

## Extension Points Summary

| Extension Point | Interface/Class | Purpose |
|-----------------|-----------------|---------|
| `com.intellij.vcs` | AbstractVcs | Main VCS implementation |
| `com.intellij.vcsRootChecker` | VcsRootChecker | Detect VCS roots |
| `com.intellij.toolWindow` | ToolWindowFactory | Create tool windows |
| `com.intellij.notificationGroup` | N/A | Register notification groups |
| `com.intellij.projectConfigurable` | Configurable | Settings UI |
| `com.intellij.projectService` | Any service class | Project-level services |

---

## Best Practices and Patterns

### 1. Threading Best Practices

**Never call VCS commands on EDT:**
```kotlin
// ❌ WRONG - Blocks UI
val status = vcs.commandExecutor.status(root)

// ✅ CORRECT - Background thread
ApplicationManager.getApplication().executeOnPooledThread {
    val status = vcs.commandExecutor.status(root)
    ApplicationManager.getApplication().invokeLater {
        updateUI(status)
    }
}
```

### 2. Change Detection Pattern

```kotlin
class MyChangeProvider(private val vcs: MyVcs) : ChangeProvider {
    override fun getChanges(
        dirtyScope: VcsDirtyScope,
        builder: ChangelistBuilder,
        progress: ProgressIndicator,
        changeListListener: ChangeListListener
    ) {
        // Already on background thread
        val statusOutput = vcs.commandExecutor.status(vcs.root)

        statusOutput.changes.forEach { change ->
            builder.processChange(change)
        }

        statusOutput.unversioned.forEach { file ->
            builder.processUnversionedFile(file)
        }
    }
}
```

### 3. Tool Window with Multiple Tabs

```kotlin
class MyToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        // Tab 1
        val panel1 = MyPanel1(project)
        val content1 = contentFactory.createContent(panel1, "Tab 1", false)
        toolWindow.contentManager.addContent(content1)

        // Tab 2
        val panel2 = MyPanel2(project)
        val content2 = contentFactory.createContent(panel2, "Tab 2", false)
        toolWindow.contentManager.addContent(content2)
    }
}
```

### 4. Error Handling

```kotlin
fun executeCommand(): CommandResult {
    return try {
        val result = commandExecutor.execute(...)
        if (!result.isSuccess) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("My VCS")
                .createNotification("Command failed", result.stderr, NotificationType.ERROR)
                .notify(project)
        }
        result
    } catch (e: Exception) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("My VCS")
            .createNotification("Error", e.message ?: "Unknown error", NotificationType.ERROR)
            .notify(project)
        CommandResult.failure(e.message ?: "Unknown error")
    }
}
```

---

## References

- [IntelliJ Platform Plugin SDK - VCS Integration](https://plugins.jetbrains.com/docs/intellij/vcs-integration-for-plugins.html)
- [Tool Windows Documentation](https://plugins.jetbrains.com/docs/intellij/tool-windows.html)
- [Notifications Documentation](https://plugins.jetbrains.com/docs/intellij/notifications.html)
- [AbstractVcs API](https://dploeger.github.io/intellij-api-doc/com/intellij/openapi/vcs/AbstractVcs.html)
- [IntelliJ Community - Git4Idea Plugin](https://github.com/JetBrains/intellij-community/tree/master/plugins/git4idea)
- [IntelliJ Community - SVN4Idea Plugin](https://github.com/JetBrains/intellij-community/tree/master/plugins/svn4idea)
- [Persisting State of Components](https://plugins.jetbrains.com/docs/intellij/persisting-state-of-components.html)
