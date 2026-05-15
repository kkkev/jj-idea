# Conflict Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add JJ conflict detection to the Changes view and wire IntelliJ's built-in 3-panel merge editor for resolution.

**Architecture:** `JujutsuChangeProvider` maps `jj status` `C` lines to `FileStatus.MERGED_WITH_CONFLICTS`; `JujutsuMergeProvider` (implements `MergeProvider`) reads the file's JJ conflict markers and provides ours/base/theirs bytes to IntelliJ's merge framework; marker parsing is hidden behind a `ConflictExtractor` strategy interface.

**Tech Stack:** Kotlin, IntelliJ Platform (`com.intellij.openapi.vcs.merge.*`), JUnit 5, Kotest assertions, MockK.

---

## File Map

**New files:**
- `src/main/kotlin/in/kkkev/jjidea/jj/JujutsuConflict.kt` — domain data class
- `src/main/kotlin/in/kkkev/jjidea/jj/conflict/ConflictExtractor.kt` — strategy interface
- `src/main/kotlin/in/kkkev/jjidea/jj/conflict/JjMarkerConflictExtractor.kt` — JJ marker parser
- `src/main/kotlin/in/kkkev/jjidea/vcs/merge/JujutsuMergeProvider.kt` — MergeProvider impl
- `src/test/kotlin/in/kkkev/jjidea/jj/conflict/JjMarkerConflictExtractorTest.kt`
- `src/test/kotlin/in/kkkev/jjidea/vcs/merge/JujutsuMergeProviderTest.kt`

**Modified files:**
- `src/main/kotlin/in/kkkev/jjidea/jj/FileChange.kt` — add `CONFLICT` enum value
- `src/main/kotlin/in/kkkev/jjidea/jj/CommandExecutor.kt` — add `resolveList()`
- `src/main/kotlin/in/kkkev/jjidea/jj/cli/CliExecutor.kt` — implement `resolveList()` + args fn
- `src/main/kotlin/in/kkkev/jjidea/vcs/JujutsuVcs.kt` — override `getMergeProvider()`
- `src/main/kotlin/in/kkkev/jjidea/vcs/changes/JujutsuChangeProvider.kt` — handle `C` status
- `src/test/kotlin/in/kkkev/jjidea/vcs/changes/JujutsuChangeProviderTest.kt` — add conflict cases
- `src/test/kotlin/in/kkkev/jjidea/contract/StubCommandExecutor.kt` — stub `resolveList()`

---

## Task 1: Domain Types and CLI Infrastructure

**Files:**
- Modify: `src/main/kotlin/in/kkkev/jjidea/jj/FileChange.kt`
- Create: `src/main/kotlin/in/kkkev/jjidea/jj/JujutsuConflict.kt`
- Create: `src/main/kotlin/in/kkkev/jjidea/jj/conflict/ConflictExtractor.kt`
- Modify: `src/main/kotlin/in/kkkev/jjidea/jj/CommandExecutor.kt`
- Modify: `src/main/kotlin/in/kkkev/jjidea/jj/cli/CliExecutor.kt`
- Modify: `src/test/kotlin/in/kkkev/jjidea/contract/StubCommandExecutor.kt`

These are all small, non-behavioural additions with no test needed beyond compilation.

- [ ] **Step 1: Add CONFLICT to FileChangeStatus**

In `src/main/kotlin/in/kkkev/jjidea/jj/FileChange.kt`, add `CONFLICT` to the enum:

```kotlin
enum class FileChangeStatus {
    MODIFIED,
    ADDED,
    DELETED,
    RENAMED,
    CONFLICT,
    UNKNOWN
}
```

- [ ] **Step 2: Create JujutsuConflict**

Create `src/main/kotlin/in/kkkev/jjidea/jj/JujutsuConflict.kt`:

```kotlin
package `in`.kkkev.jjidea.jj

import com.intellij.openapi.vcs.FilePath

/**
 * Represents a single conflicted file in the repository.
 * Used as shared infrastructure for the future Conflicts tool window.
 * The MergeProvider path operates on VirtualFile directly and does not use this class.
 */
data class JujutsuConflict(val repo: JujutsuRepository, val filePath: FilePath)
```

- [ ] **Step 3: Create ConflictExtractor interface**

Create `src/main/kotlin/in/kkkev/jjidea/jj/conflict/ConflictExtractor.kt`:

```kotlin
package `in`.kkkev.jjidea.jj.conflict

import com.intellij.openapi.vcs.merge.MergeData

/**
 * Strategy for extracting three-way merge data (ours/base/theirs) from a conflicted file's bytes.
 * Returns null if the file has no recognizable conflict markers or the format is malformed.
 */
interface ConflictExtractor {
    fun extract(fileContent: ByteArray): MergeData?
}
```

- [ ] **Step 4: Add resolveList to CommandExecutor interface**

In `src/main/kotlin/in/kkkev/jjidea/jj/CommandExecutor.kt`, add after the existing `restore()` method:

```kotlin
/**
 * List all conflicted files in the given revision.
 * Calls `jj resolve --list -r <revision>` and returns one file path per line.
 * Used as infrastructure for the future Conflicts tool window.
 */
fun resolveList(revision: Revision = WorkingCopy): CommandResult
```

- [ ] **Step 5: Add args function and implement in CliExecutor**

In `src/main/kotlin/in/kkkev/jjidea/jj/cli/CliExecutor.kt`, add an args builder function near the other args functions (e.g. after `squashArgs`):

```kotlin
internal fun resolveListArgs(revision: Revision = WorkingCopy): List<String> =
    listOf("resolve", "--list", "-r", revision.toString())
```

Then add the override in `CliExecutor` class body, near `status()`:

```kotlin
override fun resolveList(revision: Revision) = execute(root, resolveListArgs(revision))
```

- [ ] **Step 6: Stub resolveList in StubCommandExecutor**

In `src/test/kotlin/in/kkkev/jjidea/contract/StubCommandExecutor.kt`, add alongside the other TODO stubs:

```kotlin
override fun resolveList(revision: Revision): CommandExecutor.CommandResult =
    TODO("Not needed for integration tests")
```

- [ ] **Step 7: Verify compilation**

```bash
./gradlew compileKotlin compileTestKotlin
```

Expected: `BUILD SUCCESSFUL` with no errors.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/in/kkkev/jjidea/jj/FileChange.kt \
        src/main/kotlin/in/kkkev/jjidea/jj/JujutsuConflict.kt \
        src/main/kotlin/in/kkkev/jjidea/jj/conflict/ConflictExtractor.kt \
        src/main/kotlin/in/kkkev/jjidea/jj/CommandExecutor.kt \
        src/main/kotlin/in/kkkev/jjidea/jj/cli/CliExecutor.kt \
        src/test/kotlin/in/kkkev/jjidea/contract/StubCommandExecutor.kt
git commit -m "Add conflict domain types, ConflictExtractor interface, and resolveList CLI command"
```

---

## Task 2: JjMarkerConflictExtractor

**Files:**
- Create: `src/main/kotlin/in/kkkev/jjidea/jj/conflict/JjMarkerConflictExtractor.kt`
- Create: `src/test/kotlin/in/kkkev/jjidea/jj/conflict/JjMarkerConflictExtractorTest.kt`

JJ embeds conflict markers in the file when a conflict occurs. The format is:
```
<<<<<<< Conflict N of N
+++++++ Contents of side #1
[ours content]
------- Base
[base content]
+++++++ Contents of side #2
[theirs content]
>>>>>>> Conflict N of N ends
```

The extractor reconstructs three full-file byte arrays. For each panel: take all non-conflict lines verbatim, and for each conflict block substitute the appropriate side's content.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/in/kkkev/jjidea/jj/conflict/JjMarkerConflictExtractorTest.kt`:

```kotlin
package `in`.kkkev.jjidea.jj.conflict

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class JjMarkerConflictExtractorTest {
    private val extractor = JjMarkerConflictExtractor()

    @Test
    fun `single conflict block - extracts correct content for each panel`() {
        val input = """
            |context before
            |<<<<<<< Conflict 1 of 1
            |+++++++ Contents of side #1
            |ours content
            |------- Base
            |base content
            |+++++++ Contents of side #2
            |theirs content
            |>>>>>>> Conflict 1 of 1 ends
            |context after
        """.trimMargin()

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        result shouldNotBe null
        result!!.CURRENT.toString(Charsets.UTF_8) shouldBe "context before\nours content\ncontext after"
        result.ORIGINAL.toString(Charsets.UTF_8) shouldBe "context before\nbase content\ncontext after"
        result.LAST.toString(Charsets.UTF_8) shouldBe "context before\ntheirs content\ncontext after"
    }

    @Test
    fun `multiple conflict blocks - all regions substituted in each panel`() {
        val input = """
            |line1
            |<<<<<<< Conflict 1 of 2
            |+++++++ Contents of side #1
            |ours-A
            |------- Base
            |base-A
            |+++++++ Contents of side #2
            |theirs-A
            |>>>>>>> Conflict 1 of 2 ends
            |line2
            |<<<<<<< Conflict 2 of 2
            |+++++++ Contents of side #1
            |ours-B
            |------- Base
            |base-B
            |+++++++ Contents of side #2
            |theirs-B
            |>>>>>>> Conflict 2 of 2 ends
            |line3
        """.trimMargin()

        val result = extractor.extract(input.toByteArray(Charsets.UTF_8))

        result shouldNotBe null
        result!!.CURRENT.toString(Charsets.UTF_8) shouldBe "line1\nours-A\nline2\nours-B\nline3"
        result.ORIGINAL.toString(Charsets.UTF_8) shouldBe "line1\nbase-A\nline2\nbase-B\nline3"
        result.LAST.toString(Charsets.UTF_8) shouldBe "line1\ntheirs-A\nline2\ntheirs-B\nline3"
    }

    @Test
    fun `no conflict markers returns null`() {
        val input = "just regular content\nno conflicts here\n"
        extractor.extract(input.toByteArray(Charsets.UTF_8)).shouldBeNull()
    }

    @Test
    fun `unclosed conflict block returns null`() {
        val input = """
            |before
            |<<<<<<< Conflict 1 of 1
            |+++++++ Contents of side #1
            |ours
            |------- Base
            |base
        """.trimMargin()
        extractor.extract(input.toByteArray(Charsets.UTF_8)).shouldBeNull()
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew test --tests "in.kkkev.jjidea.jj.conflict.JjMarkerConflictExtractorTest"
```

Expected: compilation failure — `JjMarkerConflictExtractor` does not exist yet.

- [ ] **Step 3: Implement JjMarkerConflictExtractor**

Create `src/main/kotlin/in/kkkev/jjidea/jj/conflict/JjMarkerConflictExtractor.kt`:

```kotlin
package `in`.kkkev.jjidea.jj.conflict

import com.intellij.openapi.vcs.merge.MergeData

class JjMarkerConflictExtractor : ConflictExtractor {

    override fun extract(fileContent: ByteArray): MergeData? {
        val lines = fileContent.toString(Charsets.UTF_8).split('\n')
        val current = mutableListOf<String>()
        val original = mutableListOf<String>()
        val last = mutableListOf<String>()
        var foundAnyConflict = false
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("<<<<<<< Conflict ")) {
                foundAnyConflict = true
                i++
                val side1 = mutableListOf<String>()
                val base = mutableListOf<String>()
                val side2 = mutableListOf<String>()
                var section = Section.NONE
                var closed = false

                while (i < lines.size) {
                    val inner = lines[i]
                    when {
                        inner.startsWith(">>>>>>> Conflict ") -> { closed = true; i++; break }
                        inner.startsWith("+++++++ Contents of side #1") -> section = Section.SIDE1
                        inner.startsWith("------- Base") -> section = Section.BASE
                        inner.startsWith("+++++++ Contents of side #2") -> section = Section.SIDE2
                        else -> when (section) {
                            Section.SIDE1 -> side1.add(inner)
                            Section.BASE -> base.add(inner)
                            Section.SIDE2 -> side2.add(inner)
                            Section.NONE -> {}
                        }
                    }
                    i++
                }

                if (!closed) return null
                current.addAll(side1)
                original.addAll(base)
                last.addAll(side2)
            } else {
                current.add(line)
                original.add(line)
                last.add(line)
                i++
            }
        }

        if (!foundAnyConflict) return null

        return MergeData().also {
            it.CURRENT = current.joinToString("\n").toByteArray(Charsets.UTF_8)
            it.ORIGINAL = original.joinToString("\n").toByteArray(Charsets.UTF_8)
            it.LAST = last.joinToString("\n").toByteArray(Charsets.UTF_8)
        }
    }

    private enum class Section { NONE, SIDE1, BASE, SIDE2 }
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew test --tests "in.kkkev.jjidea.jj.conflict.JjMarkerConflictExtractorTest"
```

Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/in/kkkev/jjidea/jj/conflict/JjMarkerConflictExtractor.kt \
        src/test/kotlin/in/kkkev/jjidea/jj/conflict/JjMarkerConflictExtractorTest.kt
git commit -m "Add JjMarkerConflictExtractor with TDD"
```

---

## Task 3: JujutsuChangeProvider — Conflict Status

**Files:**
- Modify: `src/main/kotlin/in/kkkev/jjidea/vcs/changes/JujutsuChangeProvider.kt`
- Modify: `src/test/kotlin/in/kkkev/jjidea/vcs/changes/JujutsuChangeProviderTest.kt`

`jj status` outputs `C path/to/file.txt` for conflicted files. We map this to `FileStatus.MERGED_WITH_CONFLICTS`, which makes IntelliJ show the file in red in the Changes view and enables the "Resolve Conflicts…" action.

- [ ] **Step 1: Write the failing tests**

Add these two tests to `JujutsuChangeProviderTest`, inside the class body, after the existing test methods:

```kotlin
@Test
fun `single conflict`() {
    directory.addChild(getOrCreateVirtualFile(false, "foo.txt"))

    val output = statusOutput("C foo.txt")

    val changeSlot = slot<Change>()
    every {
        builder.processChange(capture(changeSlot), JujutsuVcs.getKey())
    } returns Unit

    val filePathSlot = slot<FilePath>()
    every {
        repo.createRevision(capture(filePathSlot), any())
    } answers {
        val result = mockk<ContentRevision>()
        every { result.file } returns filePathSlot.captured
        result
    }

    jcp.parseStatus(output, repo, builder)

    val change = changeSlot.captured
    change.fileStatus shouldBe FileStatus.MERGED_WITH_CONFLICTS
    change.beforeRevision?.file?.relativeTo(directory) shouldBe "foo.txt"
    change.afterRevision?.file?.relativeTo(directory) shouldBe "foo.txt"
}

@Test
fun `conflict alongside modify`() {
    directory.addChild(getOrCreateVirtualFile(false, "conflict.txt"))
    directory.addChild(getOrCreateVirtualFile(false, "modified.txt"))

    val output = statusOutput("C conflict.txt", "M modified.txt")

    val changes = mutableListOf<Change>()
    every {
        builder.processChange(capture(changes), JujutsuVcs.getKey())
    } returns Unit

    every {
        repo.createRevision(any(), any())
    } answers {
        val fp = firstArg<FilePath>()
        mockk<ContentRevision> { every { file } returns fp }
    }

    jcp.parseStatus(output, repo, builder)

    changes.map { it.fileStatus } shouldBe listOf(FileStatus.MERGED_WITH_CONFLICTS, FileStatus.MODIFIED)
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew test --tests "in.kkkev.jjidea.vcs.changes.JujutsuChangeProviderTest"
```

Expected: `single conflict` and `conflict alongside modify` fail — `C` status is unhandled and produces no change.

- [ ] **Step 3: Add the C case to JujutsuChangeProvider**

In `src/main/kotlin/in/kkkev/jjidea/vcs/changes/JujutsuChangeProvider.kt`, in `parseStatusLine()`, add `'C'` alongside the other status characters:

```kotlin
'C' -> addConflictedChange(path, repo, builder)
```

Then add the private method after `addDeletedChange()`:

```kotlin
private fun addConflictedChange(path: FilePath, repo: JujutsuRepository, builder: ChangelistBuilder) {
    val beforeRevision = repo.createRevision(path, repo.workingCopyParent())
    val afterRevision = CurrentContentRevision(path)
    builder.processChange(
        Change(beforeRevision, afterRevision, FileStatus.MERGED_WITH_CONFLICTS),
        vcs.keyInstanceMethod
    )
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew test --tests "in.kkkev.jjidea.vcs.changes.JujutsuChangeProviderTest"
```

Expected: `BUILD SUCCESSFUL`, all tests passed.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/in/kkkev/jjidea/vcs/changes/JujutsuChangeProvider.kt \
        src/test/kotlin/in/kkkev/jjidea/vcs/changes/JujutsuChangeProviderTest.kt
git commit -m "Handle C status in JujutsuChangeProvider for conflict detection"
```

---

## Task 4: JujutsuMergeProvider and VCS Registration

**Files:**
- Create: `src/main/kotlin/in/kkkev/jjidea/vcs/merge/JujutsuMergeProvider.kt`
- Create: `src/test/kotlin/in/kkkev/jjidea/vcs/merge/JujutsuMergeProviderTest.kt`
- Modify: `src/main/kotlin/in/kkkev/jjidea/vcs/JujutsuVcs.kt`

`MergeProvider` is the hook IntelliJ calls to populate the 3-panel merge editor. Registering it on `JujutsuVcs` via `getMergeProvider()` is what makes "Resolve Conflicts…" appear automatically in the Changes view toolbar and context menu for files with `FileStatus.MERGED_WITH_CONFLICTS`.

`conflictResolvedForFile()` is intentionally a no-op: JJ auto-detects that a conflict is resolved when it runs `jj status` and finds no conflict markers in the file. No extra CLI call is needed.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/in/kkkev/jjidea/vcs/merge/JujutsuMergeProviderTest.kt`:

```kotlin
package `in`.kkkev.jjidea.vcs.merge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.fileTypes.UnknownFileType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class JujutsuMergeProviderTest {
    private val project = mockk<Project>()
    private val provider = JujutsuMergeProvider(project)

    @Test
    fun `loadRevisions - conflict content - returns correct MergeData`() {
        val content = """
            |<<<<<<< Conflict 1 of 1
            |+++++++ Contents of side #1
            |ours
            |------- Base
            |base
            |+++++++ Contents of side #2
            |theirs
            |>>>>>>> Conflict 1 of 1 ends
        """.trimMargin()

        val file = mockk<VirtualFile>()
        every { file.contentsToByteArray() } returns content.toByteArray(Charsets.UTF_8)

        val result = provider.loadRevisions(file)

        result.CURRENT.toString(Charsets.UTF_8) shouldBe "ours"
        result.ORIGINAL.toString(Charsets.UTF_8) shouldBe "base"
        result.LAST.toString(Charsets.UTF_8) shouldBe "theirs"
    }

    @Test
    fun `loadRevisions - no conflict markers - throws VcsException`() {
        val file = mockk<VirtualFile>()
        every { file.contentsToByteArray() } returns "no conflicts here".toByteArray()
        every { file.name } returns "test.txt"

        shouldThrow<VcsException> { provider.loadRevisions(file) }
    }

    @Test
    fun `isBinary - binary file type - returns true`() {
        val file = mockk<VirtualFile>()
        every { file.fileType } returns mockk { every { isBinary } returns true }

        provider.isBinary(file) shouldBe true
    }

    @Test
    fun `isBinary - text file type - returns false`() {
        val file = mockk<VirtualFile>()
        every { file.fileType } returns UnknownFileType.INSTANCE

        provider.isBinary(file) shouldBe false
    }
}
```

- [ ] **Step 2: Run to confirm failure**

```bash
./gradlew test --tests "in.kkkev.jjidea.vcs.merge.JujutsuMergeProviderTest"
```

Expected: compilation failure — `JujutsuMergeProvider` does not exist yet.

- [ ] **Step 3: Create JujutsuMergeProvider**

Create `src/main/kotlin/in/kkkev/jjidea/vcs/merge/JujutsuMergeProvider.kt`:

```kotlin
package `in`.kkkev.jjidea.vcs.merge

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.merge.MergeData
import com.intellij.openapi.vcs.merge.MergeProvider
import com.intellij.openapi.vfs.VirtualFile
import `in`.kkkev.jjidea.jj.conflict.ConflictExtractor
import `in`.kkkev.jjidea.jj.conflict.JjMarkerConflictExtractor

// TODO: Upgrade to MergeProvider2 to support bulk Accept Yours / Accept Theirs
class JujutsuMergeProvider(private val project: Project) : MergeProvider {
    private val extractor: ConflictExtractor = JjMarkerConflictExtractor()

    override fun loadRevisions(file: VirtualFile): MergeData =
        extractor.extract(file.contentsToByteArray())
            ?: throw VcsException("Could not extract conflict data from ${file.name}")

    override fun conflictResolvedForFile(file: VirtualFile) {
        // JJ auto-detects resolution when conflict markers are absent on next status refresh
    }

    override fun isBinary(file: VirtualFile) = file.fileType.isBinary
}
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew test --tests "in.kkkev.jjidea.vcs.merge.JujutsuMergeProviderTest"
```

Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 5: Register MergeProvider on JujutsuVcs**

In `src/main/kotlin/in/kkkev/jjidea/vcs/JujutsuVcs.kt`, add a lazy field and override `getMergeProvider()`:

```kotlin
private val lazyMergeProvider by lazy { JujutsuMergeProvider(myProject) }

override fun getMergeProvider() = lazyMergeProvider
```

Place `lazyMergeProvider` alongside the other `lazy` fields at the top of the class, and add the import:

```kotlin
import `in`.kkkev.jjidea.vcs.merge.JujutsuMergeProvider
```

- [ ] **Step 6: Verify compilation and full test suite**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all existing tests still pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/in/kkkev/jjidea/vcs/merge/JujutsuMergeProvider.kt \
        src/test/kotlin/in/kkkev/jjidea/vcs/merge/JujutsuMergeProviderTest.kt \
        src/main/kotlin/in/kkkev/jjidea/vcs/JujutsuVcs.kt
git commit -m "Add JujutsuMergeProvider and register with JujutsuVcs"
```

---

## Task 5: Manual Verification

- [ ] **Step 1: Run the IDE**

```bash
./gradlew runIde
```

- [ ] **Step 2: Create a conflict scenario**

Open a terminal in a JJ repo and create a conflict:

```bash
# In a jj repo
echo "original" > test.txt
jj new -m "side A" && echo "side A content" > test.txt
jj new -m "side B" -r @- && echo "side B content" > test.txt
jj new -m "merge" @- @  # creates a merge commit with a conflict
```

- [ ] **Step 3: Verify Changes view**

In the IDE, open the Changes view (View → Tool Windows → Changes or `Ctrl+Alt+H`). The conflicted `test.txt` should appear in **red** with the conflict status.

- [ ] **Step 4: Verify merge editor**

Right-click `test.txt` → "Resolve Conflicts…". The standard IntelliJ 3-panel merge editor should open with ours (left), base (center), and theirs (right) panels populated from the JJ conflict markers.

- [ ] **Step 5: Verify resolution**

Edit the result panel to resolve the conflict and save. Close the merge editor. Trigger a VCS refresh (`Ctrl+Alt+Shift+F5`). `test.txt` should no longer appear as conflicted in the Changes view.

---

## Self-Review Checklist

- Spec goal: conflicted files in Changes view → ✅ Task 3
- Spec goal: 3-panel merge editor → ✅ Task 4
- Spec goal: ConflictExtractor strategy interface → ✅ Tasks 1 & 2
- Spec goal: `resolveList` CLI infrastructure → ✅ Task 1
- Spec goal: `JujutsuConflict` domain class → ✅ Task 1
- Spec goal: `FileChangeStatus.CONFLICT` → ✅ Task 1
- Spec goal: TODO comment for MergeProvider2 → ✅ Task 4 Step 3
- All method signatures consistent across tasks → ✅ verified
- No TBD/TODO placeholders in plan steps → ✅ verified
