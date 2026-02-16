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
import `in`.kkkev.jjidea.jj.JujutsuRepository
import `in`.kkkev.jjidea.jj.Revision
import `in`.kkkev.jjidea.vcs.JujutsuVcs
import `in`.kkkev.jjidea.vcs.relativeTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.*
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
    fun `add non-existent file silently ignores`() {
        val output = statusOutput("A foo.txt")

        jcp.parseStatus(output, repo, builder)

        verify { builder wasNot Called }
    }

    @Test
    fun `single modify`() {
        directory.addChild(getOrCreateVirtualFile(false, "foo.txt"))

        val output = statusOutput("M foo.txt")

        val changeSlot = slot<Change>()
        every {
            builder.processChange(capture(changeSlot), JujutsuVcs.getKey())
        } returns Unit

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
        val revisionSlot = slot<Revision>()
        every {
            repo.createRevision(capture(filePathSlot), capture(revisionSlot))
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
        val revisionSlot = slot<Revision>()
        every {
            repo.createRevision(capture(filePathSlot), capture(revisionSlot))
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
        val revisionSlot = slot<Revision>()
        every {
            repo.createRevision(capture(filePathSlot), capture(revisionSlot))
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
    fun `move to new subdirectory`() {
        val subdir = getOrCreateVirtualFile(true, "bar")
        subdir.addChild(getOrCreateVirtualFile(false, "bar.txt"))

        val output = statusOutput("R {foo => bar}/bar.txt")

        val changeSlot = slot<Change>()
        every {
            builder.processChange(capture(changeSlot), JujutsuVcs.getKey())
        } returns Unit

        val filePathSlot = slot<FilePath>()
        val revisionSlot = slot<Revision>()
        every {
            repo.createRevision(capture(filePathSlot), capture(revisionSlot))
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
}

private fun statusOutput(vararg lines: String) =
    """
    Working copy changes:
    ${lines.joinToString("\n")}
    Working copy  (@) : zstylrqo 308df46b original
    Parent commit (@-): vszmmkts 4463701e (empty) b1.1
    """.trimIndent()
