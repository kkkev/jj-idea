package `in`.kkkev.jjidea.actions.git

import `in`.kkkev.jjidea.jj.GitRemote
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RemoteUrlBuilderTest {
    private val hash = "abc123def456abc123def456abc123def456abc1"

    @Nested
    inner class `parseBaseUrl` {
        @Test
        fun `GitHub SSH`() {
            RemoteUrlBuilder.parseBaseUrl("git@github.com:user/repo.git") shouldBe
                ("https://github.com/user/repo" to RemoteKind.GITHUB)
        }

        @Test
        fun `GitHub SSH without git suffix`() {
            RemoteUrlBuilder.parseBaseUrl("git@github.com:user/repo") shouldBe
                ("https://github.com/user/repo" to RemoteKind.GITHUB)
        }

        @Test
        fun `GitHub HTTPS`() {
            RemoteUrlBuilder.parseBaseUrl("https://github.com/user/repo.git") shouldBe
                ("https://github.com/user/repo" to RemoteKind.GITHUB)
        }

        @Test
        fun `GitHub HTTPS without git suffix`() {
            RemoteUrlBuilder.parseBaseUrl("https://github.com/user/repo") shouldBe
                ("https://github.com/user/repo" to RemoteKind.GITHUB)
        }

        @Test
        fun `GitLab SSH`() {
            RemoteUrlBuilder.parseBaseUrl("git@gitlab.com:user/repo.git") shouldBe
                ("https://gitlab.com/user/repo" to RemoteKind.GITLAB)
        }

        @Test
        fun `GitLab HTTPS`() {
            RemoteUrlBuilder.parseBaseUrl("https://gitlab.com/group/subgroup/repo.git") shouldBe
                ("https://gitlab.com/group/subgroup/repo" to RemoteKind.GITLAB)
        }

        @Test
        fun `unknown host returns null`() {
            RemoteUrlBuilder.parseBaseUrl("https://bitbucket.org/user/repo.git") shouldBe null
        }

        @Test
        fun `self-hosted returns null`() {
            RemoteUrlBuilder.parseBaseUrl("https://git.mycompany.com/user/repo.git") shouldBe null
        }

        @Test
        fun `SSH self-hosted returns null`() {
            RemoteUrlBuilder.parseBaseUrl("git@git.mycompany.com:user/repo.git") shouldBe null
        }
    }

    @Nested
    inner class `recognizedRemotes` {
        @Test
        fun `GitHub commit URL uses slash-commit`() {
            val result = recognizedRemotes(listOf("origin https://github.com/user/repo.git"), hash)
            result.single().commitUrl shouldBe "https://github.com/user/repo/commit/$hash"
        }

        @Test
        fun `GitLab commit URL uses dash-slash-commit`() {
            val result = recognizedRemotes(listOf("origin https://gitlab.com/user/repo.git"), hash)
            result.single().commitUrl shouldBe "https://gitlab.com/user/repo/-/commit/$hash"
        }

        @Test
        fun `multiple remotes returns one entry per recognized remote`() {
            val result = recognizedRemotes(
                listOf(
                    "origin git@github.com:user/repo.git",
                    "upstream git@gitlab.com:user/repo.git",
                    "backup git@bitbucket.org:user/repo.git"
                ),
                hash
            )
            result.map { it.name } shouldBe listOf("origin", "upstream")
        }

        @Test
        fun `unrecognized remote returns empty list`() {
            recognizedRemotes(listOf("origin git@bitbucket.org:user/repo.git"), hash) shouldBe emptyList()
        }

        @Test
        fun `isPushed true propagates to all remotes`() {
            val result = recognizedRemotes(
                listOf("origin https://github.com/user/repo.git", "upstream https://gitlab.com/user/repo.git"),
                hash,
                isPushed = true
            )
            result.all { it.isPushed } shouldBe true
        }

        @Test
        fun `isPushed false propagates to all remotes`() {
            val result = recognizedRemotes(
                listOf("origin https://github.com/user/repo.git"),
                hash,
                isPushed = false
            )
            result.single().isPushed shouldBe false
        }
    }

    @Nested
    inner class `classifiedRemotes` {
        @Test
        fun `GitHub SSH remote classified correctly`() {
            val result = RemoteUrlBuilder.classifiedRemotes(listOf(GitRemote("origin", "git@github.com:user/repo.git")))
            result.single() shouldBe ClassifiedRemote("origin", "https://github.com/user/repo", RemoteKind.GITHUB)
        }

        @Test
        fun `GitLab HTTPS remote classified correctly`() {
            val result = RemoteUrlBuilder.classifiedRemotes(listOf(GitRemote("gl", "https://gitlab.com/user/repo.git")))
            result.single() shouldBe ClassifiedRemote("gl", "https://gitlab.com/user/repo", RemoteKind.GITLAB)
        }

        @Test
        fun `unknown host filtered out`() {
            val result = RemoteUrlBuilder.classifiedRemotes(
                listOf(GitRemote("bb", "https://bitbucket.org/user/repo.git"))
            )
            result shouldBe emptyList()
        }

        @Test
        fun `mixed remotes - only recognized ones returned`() {
            val result = RemoteUrlBuilder.classifiedRemotes(
                listOf(
                    GitRemote("origin", "git@github.com:user/repo.git"),
                    GitRemote("bb", "https://bitbucket.org/user/repo.git"),
                    GitRemote("gl", "https://gitlab.com/user/repo.git")
                )
            )
            result.map { it.name } shouldBe listOf("origin", "gl")
        }
    }

    @Nested
    inner class `file URLs` {
        @Test
        fun `GitHub file URL uses blob`() {
            val url = RemoteUrlBuilder.fileUrl("https://github.com/user/repo", RemoteKind.GITHUB, hash, "src/Foo.kt")
            url shouldBe "https://github.com/user/repo/blob/$hash/src/Foo.kt"
        }

        @Test
        fun `GitLab file URL uses dash-slash-blob`() {
            val url = RemoteUrlBuilder.fileUrl("https://gitlab.com/user/repo", RemoteKind.GITLAB, hash, "src/Foo.kt")
            url shouldBe "https://gitlab.com/user/repo/-/blob/$hash/src/Foo.kt"
        }

        @Test
        fun `no line range produces no fragment`() {
            fileUrl(RemoteKind.GITHUB, null) shouldBe "https://github.com/user/repo/blob/$hash/src/Foo.kt"
        }

        @Test
        fun `GitHub single line appends L fragment`() {
            fileUrl(RemoteKind.GITHUB, 42..42) shouldBe "https://github.com/user/repo/blob/$hash/src/Foo.kt#L42"
        }

        @Test
        fun `GitHub line range appends L-L fragment`() {
            fileUrl(RemoteKind.GITHUB, 42..50) shouldBe "https://github.com/user/repo/blob/$hash/src/Foo.kt#L42-L50"
        }

        @Test
        fun `GitLab single line appends L fragment`() {
            fileUrl(RemoteKind.GITLAB, 42..42) shouldBe "https://gitlab.com/user/repo/-/blob/$hash/src/Foo.kt#L42"
        }

        @Test
        fun `GitLab line range appends L-N fragment without second L`() {
            fileUrl(RemoteKind.GITLAB, 42..50) shouldBe "https://gitlab.com/user/repo/-/blob/$hash/src/Foo.kt#L42-50"
        }
    }

    private fun fileUrl(kind: RemoteKind, lineRange: IntRange?) =
        RemoteUrlBuilder.fileUrl(
            if (kind == RemoteKind.GITLAB) "https://gitlab.com/user/repo" else "https://github.com/user/repo",
            kind,
            hash,
            "src/Foo.kt",
            lineRange
        )

    /** Parse a list of simulated `jj git remote list` output lines into [RecognizedRemote]s. */
    private fun recognizedRemotes(lines: List<String>, commitHash: String, isPushed: Boolean = false) =
        RemoteUrlBuilder.recognizedRemotes(
            lines.map { GitRemote(it.substringBefore(' '), it.substringAfter(' ', "").trim()) },
            commitHash,
            isPushed
        )
}
