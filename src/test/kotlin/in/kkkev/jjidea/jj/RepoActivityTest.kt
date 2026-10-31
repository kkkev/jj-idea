package `in`.kkkev.jjidea.jj

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RepoActivityTest {
    @Test
    fun `busy only while a tracked block runs, per repo`() {
        val seen = mutableListOf<Boolean>()
        RepoActivity.track("/repo/a") {
            seen += RepoActivity.isBusy("/repo/a")
            seen += RepoActivity.isBusy("/repo/b")
        }
        seen shouldBe listOf(true, false)
        RepoActivity.isBusy("/repo/a") shouldBe false
    }

    @Test
    fun `nested commands keep the repo busy until the outermost finishes`() {
        RepoActivity.track("/repo/n") {
            RepoActivity.track("/repo/n") { }
            RepoActivity.isBusy("/repo/n") shouldBe true
        }
        RepoActivity.isBusy("/repo/n") shouldBe false
    }

    @Test
    fun `an exception still clears the busy mark`() {
        runCatching { RepoActivity.track("/repo/x") { error("boom") } }
        RepoActivity.isBusy("/repo/x") shouldBe false
    }

    @Test
    fun `a null path is not tracked`() {
        RepoActivity.track(null) { "ok" } shouldBe "ok"
    }
}
