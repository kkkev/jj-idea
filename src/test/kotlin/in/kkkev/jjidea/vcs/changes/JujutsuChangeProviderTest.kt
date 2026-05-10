package `in`.kkkev.jjidea.vcs.changes

import com.intellij.mock.MockApplication
import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsRoot
import com.intellij.openapi.vcs.actions.VcsContextFactory
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangelistBuilder
import com.intellij.openapi.vcs.changes.ContentRevision
import `in`.kkkev.jjidea.jj.ContentLocator
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.relativeTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class JujutsuChangeProviderTest {
    private val project = mockk<Project>()
    private val vcs = JujutsuVcs(project)
    private val vcsContextFactory = mockk<VcsContextFactory>()
    private val jcp = JujutsuChangeProvider(vcs)
    private val repo = mockk<JujutsuRepository>()
    private val builder = mockk<ChangelistBuilder>()

    private val virtualFileCache = mutableMapOf<String, MockVirtualFile>()
    private fun getOrCreateVirtualFile(isDirectory: Boolean, name: String) =
        virtualFileCache.computeIfAbsent(name) { MockVirtualFile(isDirectory, name) }

    private val directory = getOrCreateVirtualFile(true, "/wadger")

    @BeforeEach
    fun setupMocks() {
        val application = MockApplication(mockk<Disposable>())
        ApplicationManager.setApplication(application)
        application.registerService(VcsContextFactory::class.java, vcsContextFactory)

        every { repo.directory } returns directory
        every { repo.workingCopy.parentContentLocator } returns mockk<ContentLocator>()

        val pathStringSlot = slot<String>()
        val isDirectorySlot = slot<Boolean>()
        every { vcsContextFactory.createFilePath(capture(pathStringSlot), capture(isDirectorySlot)) } answers {
            MockFilePath(pathStringSlot.captured, isDirectorySlot.captured)
        }

        val projectManager = mockk<ProjectManager>()
        application.registerService(ProjectManager::class.java, projectManager)
        every { projectManager.openProjects } returns arrayOf(project)

        val projectLevelVcsManager = mockk<ProjectLevelVcsManager>()
        application.registerService(ProjectLevelVcsManager::class.java, projectLevelVcsManager)
        every { projectLevelVcsManager.getVcsFor(any<FilePath>()) } returns vcs
        every { projectLevelVcsManager.getVcsRootFor(any<FilePath>()) } returns directory
        every { projectLevelVcsManager.allVcsRoots } returns arrayOf(VcsRoot(vcs, directory))
        every { project.getService(ProjectLevelVcsManager::class.java) } returns projectLevelVcsManager
        every { project.isDisposed } returns false
    }

    inner class MockFilePath(private val name: String, private val isDirectory: Boolean) : FilePath {
        override fun isDirectory() = isDirectory
        override fun getVirtualFile() = getOrCreateVirtualFile(isDirectory, name)
        override fun getVirtualFileParent() = virtualFile.parent
        override fun getIOFile() = TODO("Not yet implemented")
        override fun getName() = name
        override fun getPresentableUrl() = TODO("Not yet implemented")
        override fun getCharset() = TODO("Not yet implemented")
        override fun getCharset(project: Project?) = TODO("Not yet implemented")
        override fun getFileType() = TODO("Not yet implemented")
        override fun getPath() = name
        override fun isUnder(parent: FilePath, strict: Boolean) = TODO("Not yet implemented")
        override fun getParentPath() = name.substringBeforeLast('/', "")
            .substringAfter("MOCK_ROOT:/")
            .takeIf { it.isNotEmpty() }
            ?.let { MockFilePath(it, true) }

        override fun isNonLocal() = TODO("Not yet implemented")

        override fun toString() = name + if (isDirectory) "/" else ""
    }

    @Test
    fun `single add`() {
        directory.addChild(MockVirtualFile("foo.txt"))

        val output = statusOutput("A foo.txt")

        val changeSlot = slot<Change>()
        every {
            builder.processChange(capture(changeSlot), JujutsuVcs.getKey())
        } returns Unit

        jcp.parseStatus(output, repo, builder)

        val change = changeSlot.captured
        change.fileStatus shouldBe FileStatus.ADDED
        change.beforeRevision.shouldBeNull()
        change.afterRevision?.file?.relativeTo(directory) shouldBe "foo.txt"
    }

    @Test
    fun `single modify`() {
        directory.addChild(getOrCreateVirtualFile(false, "foo.txt"))

        val output = statusOutput("M foo.txt")

        val changeSlot = slot<Change>()
        every {
            builder.processChange(capture(changeSlot), JujutsuVcs.getKey())
        } returns Unit

        val filePathSlot = slot<FilePath>()
        every {
            repo.createContentRevision(capture(filePathSlot), any<ContentLocator>())
        } answers {
            val result = mockk<ContentRevision>()
            every { result.file } returns filePathSlot.captured
            result
        }

        jcp.parseStatus(output, repo, builder)

        val change = changeSlot.captured
        change.fileStatus shouldBe FileStatus.MODIFIED
        change.beforeRevision?.file?.relativeTo(directory) shouldBe "foo.txt"
        change.afterRevision?.file?.relativeTo(directory) shouldBe "foo.txt"
    }

    @Test
    fun `single delete`() {
        val output = statusOutput("D foo.txt")

        val changeSlot = slot<Change>()
        every {
            builder.processChange(capture(changeSlot), JujutsuVcs.getKey())
        } returns Unit

        val filePathSlot = slot<FilePath>()
        every {
            repo.createContentRevision(capture(filePathSlot), any<ContentLocator>())
        } answers {
            val result = mockk<ContentRevision>()
            every { result.file } returns filePathSlot.captured
            result
        }

        jcp.parseStatus(output, repo, builder)

        val change = changeSlot.captured
        change.fileStatus shouldBe FileStatus.DELETED
        change.beforeRevision?.file?.relativeTo(directory) shouldBe "foo.txt"
        change.afterRevision?.file?.relativeTo(directory).shouldBeNull()
    }

    @Test
    fun `simple rename`() {
        directory.addChild(getOrCreateVirtualFile(false, "bar.txt"))

        val output = statusOutput("R {foo.txt => bar.txt}")

        val changeSlot = slot<Change>()
        every {
            builder.processChange(capture(changeSlot), JujutsuVcs.getKey())
        } returns Unit

        val filePathSlot = slot<FilePath>()
        val contentLocatorSlot = slot<ContentLocator>()
        every {
            repo.createContentRevision(capture(filePathSlot), capture(contentLocatorSlot))
        } answers {
            val result = mockk<ContentRevision>()
            every { result.file } returns filePathSlot.captured
            result
        }

        jcp.parseStatus(output, repo, builder)

        val change = changeSlot.captured
        change.fileStatus shouldBe FileStatus.MODIFIED
        change.beforeRevision?.file?.relativeTo(directory) shouldBe "foo.txt"
        change.afterRevision?.file?.relativeTo(directory) shouldBe "bar.txt"
    }

    @Test
    fun `rename across subdirectories`() {
        val subdir = getOrCreateVirtualFile(true, "bar")
        subdir.addChild(getOrCreateVirtualFile(false, "bam.txt"))

        val output = statusOutput("R {foo/bar.txt => bar/bam.txt}")

        val changeSlot = slot<Change>()
        every {
            builder.processChange(capture(changeSlot), JujutsuVcs.getKey())
        } returns Unit

        val filePathSlot = slot<FilePath>()
        val contentLocatorSlot = slot<ContentLocator>()
        every {
            repo.createContentRevision(capture(filePathSlot), capture(contentLocatorSlot))
        } answers {
            val result = mockk<ContentRevision>()
            every { result.file } returns filePathSlot.captured
            result
        }

        jcp.parseStatus(output, repo, builder)

        val change = changeSlot.captured
        change.fileStatus shouldBe FileStatus.MODIFIED
        change.beforeRevision?.file?.relativeTo(directory) shouldBe "foo/bar.txt"
        change.afterRevision?.file?.relativeTo(directory) shouldBe "bar/bam.txt"
    }

    @Test
    fun `rename in subdirectoru`() {
        val subdir = getOrCreateVirtualFile(true, "foo")
        subdir.addChild(getOrCreateVirtualFile(false, "bam.txt"))

        val output = statusOutput("R foo/{bar.txt => bam.txt}")

        val changeSlot = slot<Change>()
        every {
            builder.processChange(capture(changeSlot), JujutsuVcs.getKey())
        } returns Unit

        val filePathSlot = slot<FilePath>()
        val contentLocatorSlot = slot<ContentLocator>()
        every {
            repo.createContentRevision(capture(filePathSlot), capture(contentLocatorSlot))
        } answers {
            val result = mockk<ContentRevision>()
            every { result.file } returns filePathSlot.captured
            result
        }

        jcp.parseStatus(output, repo, builder)

        val change = changeSlot.captured
        change.fileStatus shouldBe FileStatus.MODIFIED
        change.beforeRevision?.file?.relativeTo(directory) shouldBe "foo/bar.txt"
        change.afterRevision?.file?.relativeTo(directory) shouldBe "foo/bam.txt"
    }

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

    @Test
    fun `move to new subdirectory`() {
        val subdir = getOrCreateVirtualFile(true, "bar")
        subdir.addChild(getOrCreateVirtualFile(false, "bar.txt"))

        val output = statusOutput("R {foo => bar}/bar.txt")

        val changeSlot = slot<Change>()
        every {
            builder.processChange(capture(changeSlot), JujutsuVcs.getKey())
        } returns Unit

        val filePathSlot = slot<FilePath>()
        val contentLocatorSlot = slot<ContentLocator>()
        every {
            repo.createContentRevision(capture(filePathSlot), capture(contentLocatorSlot))
        } answers {
            val result = mockk<ContentRevision>()
            every { result.file } returns filePathSlot.captured
            result
        }

        jcp.parseStatus(output, repo, builder)

        val change = changeSlot.captured
        change.fileStatus shouldBe FileStatus.MODIFIED
        change.beforeRevision?.file?.relativeTo(directory) shouldBe "foo/bar.txt"
        change.afterRevision?.file?.relativeTo(directory) shouldBe "bar/bar.txt"
    }

    @Test
    fun `modified file that also appears in conflict warning is reported as conflicted not modified`() {
        directory.addChild(getOrCreateVirtualFile(false, "conflict.txt"))
        directory.addChild(getOrCreateVirtualFile(false, "clean.txt"))

        val output = mixedConflictStatus(
            listOf("M conflict.txt", "M clean.txt"),
            listOf("conflict.txt    2-sided conflict"),
        )

        val changes = mutableListOf<Change>()
        every { builder.processChange(capture(changes), JujutsuVcs.getKey()) } returns Unit

        every { repo.createRevision(any(), any()) } answers {
            val fp = firstArg<FilePath>()
            mockk<ContentRevision> { every { file } returns fp }
        }

        jcp.parseStatus(output, repo, builder)

        changes.map { it.fileStatus } shouldBe listOf(FileStatus.MERGED_WITH_CONFLICTS, FileStatus.MODIFIED)
        changes.find { it.fileStatus == FileStatus.MERGED_WITH_CONFLICTS }
            ?.afterRevision?.file?.relativeTo(directory) shouldBe "conflict.txt"
    }

    @Test
    fun `secondary warning section lines are not parsed as conflict paths`() {
        directory.addChild(getOrCreateVirtualFile(false, "conflict.txt"))

        val output = mixedConflictStatus(
            listOf("M conflict.txt"),
            listOf("conflict.txt    2-sided conflict"),
            trailingWarning = "Warning: These bookmarks have conflicts:",
        )

        val changes = mutableListOf<Change>()
        every { builder.processChange(capture(changes), JujutsuVcs.getKey()) } returns Unit

        every { repo.createRevision(any(), any()) } answers {
            val fp = firstArg<FilePath>()
            mockk<ContentRevision> { every { file } returns fp }
        }

        jcp.parseStatus(output, repo, builder)

        changes.size shouldBe 1
        changes[0].fileStatus shouldBe FileStatus.MERGED_WITH_CONFLICTS
    }

    @Test
    fun `empty merge with conflict warning section - detects conflicted file`() {
        directory.addChild(getOrCreateVirtualFile(false, "conflict-test.txt"))

        val output = emptyMergeWithConflicts("conflict-test.txt    2-sided conflict")

        val changeSlot = slot<Change>()
        every { builder.processChange(capture(changeSlot), JujutsuVcs.getKey()) } returns Unit

        val filePathSlot = slot<FilePath>()
        every { repo.createRevision(capture(filePathSlot), any()) } answers {
            mockk<ContentRevision> { every { file } returns filePathSlot.captured }
        }

        jcp.parseStatus(output, repo, builder)

        val change = changeSlot.captured
        change.fileStatus shouldBe FileStatus.MERGED_WITH_CONFLICTS
        change.beforeRevision?.file?.relativeTo(directory) shouldBe "conflict-test.txt"
        change.afterRevision?.file?.relativeTo(directory) shouldBe "conflict-test.txt"
    }

    @Test
    fun `empty merge with multiple conflicts in warning section`() {
        directory.addChild(getOrCreateVirtualFile(false, "a.txt"))
        directory.addChild(getOrCreateVirtualFile(false, "b.txt"))

        val output = emptyMergeWithConflicts(
            "a.txt    2-sided conflict",
            "b.txt    2-sided conflict",
        )

        val changes = mutableListOf<Change>()
        every { builder.processChange(capture(changes), JujutsuVcs.getKey()) } returns Unit

        every { repo.createRevision(any(), any()) } answers {
            val fp = firstArg<FilePath>()
            mockk<ContentRevision> { every { file } returns fp }
        }

        jcp.parseStatus(output, repo, builder)

        changes.map { it.fileStatus } shouldBe listOf(
            FileStatus.MERGED_WITH_CONFLICTS,
            FileStatus.MERGED_WITH_CONFLICTS,
        )
    }

    @Test
    fun `long path with single-space separator in warning section is detected as conflict`() {
        val longPath = "gateway/lib/gatewaysnapshotvolatileservice.go"
        directory.addChild(getOrCreateVirtualFile(false, longPath))
        directory.addChild(getOrCreateVirtualFile(false, "model/outgoingdatasender.go"))

        val output = mixedConflictStatus(
            listOf("M $longPath", "M model/outgoingdatasender.go"),
            listOf("$longPath 2-sided conflict"),
        )

        val changes = mutableListOf<Change>()
        every { builder.processChange(capture(changes), JujutsuVcs.getKey()) } returns Unit
        every { repo.createRevision(any(), any()) } answers {
            val fp = firstArg<FilePath>()
            mockk<ContentRevision> { every { file } returns fp }
        }

        jcp.parseStatus(output, repo, builder)

        changes.map { it.fileStatus } shouldBe listOf(FileStatus.MERGED_WITH_CONFLICTS, FileStatus.MODIFIED)
        changes.find { it.fileStatus == FileStatus.MERGED_WITH_CONFLICTS }
            ?.afterRevision?.file?.relativeTo(directory) shouldBe longPath
    }

    @Test
    fun `parseConflictPaths parses jj resolve -l output`() {
        val output = """
            gateway/lib/gatewaysnapshotvolatileservice.go 2-sided conflict
            gateway/lib/gatewaywrapper.go       2-sided conflict
            gateway/lib/snapshotworker.go       2-sided conflict including 1 deletion
        """.trimIndent()

        val paths = jcp.parseConflictPaths(output)

        paths shouldBe setOf(
            "gateway/lib/gatewaysnapshotvolatileservice.go",
            "gateway/lib/gatewaywrapper.go",
            "gateway/lib/snapshotworker.go",
        )
    }

    @Test
    fun `explicit conflict paths from resolveList override warning section`() {
        val longPath = "gateway/lib/gatewaysnapshotvolatileservice.go"
        directory.addChild(getOrCreateVirtualFile(false, longPath))
        directory.addChild(getOrCreateVirtualFile(false, "model/clean.go"))

        val statusOutput = mixedConflictStatus(
            listOf("M $longPath", "M model/clean.go"),
            emptyList(),
        )
        val explicitConflictPaths = setOf(longPath)

        val changes = mutableListOf<Change>()
        every { builder.processChange(capture(changes), JujutsuVcs.getKey()) } returns Unit
        every { repo.createRevision(any(), any()) } answers {
            val fp = firstArg<FilePath>()
            mockk<ContentRevision> { every { file } returns fp }
        }

        jcp.parseStatus(statusOutput, repo, builder, explicitConflictPaths)

        changes.map { it.fileStatus } shouldBe listOf(FileStatus.MERGED_WITH_CONFLICTS, FileStatus.MODIFIED)
    }
}

private fun statusOutput(vararg lines: String) =
    """
    Working copy changes:
    ${lines.joinToString("\n")}
    Working copy  (@) : zstylrqo 308df46b original
    Parent commit (@-): vszmmkts 4463701e (empty) b1.1
    """.trimIndent()

private fun emptyMergeWithConflicts(vararg conflictLines: String) =
    """
    The working copy has no changes.
    Working copy  (@) : tvqlyorq 90595c38 (conflict) (empty) merge
    Parent commit (@-): nmzxrwpn 70f4d821 test-side-a* | side A
    Parent commit (@-): vnwpmmrk 6e92b484 test-side-b* | side B
    Warning: There are unresolved conflicts at these paths:
    ${conflictLines.joinToString("\n")}
    """.trimIndent()

private fun mixedConflictStatus(
    statusLines: List<String>,
    conflictLines: List<String>,
    trailingWarning: String = "",
) = """
    Working copy changes:
    ${statusLines.joinToString("\n")}
    Working copy  (@) : opwrwloq 562fb59e (conflict) Some commit
    Parent commit (@-): abc123 Previous commit
    Warning: There are unresolved conflicts at these paths:
    ${conflictLines.joinToString("\n")}
    $trailingWarning
    """.trimIndent()
