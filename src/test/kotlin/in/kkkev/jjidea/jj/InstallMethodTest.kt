package `in`.kkkev.jjidea.jj

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Tests for [InstallMethod] - jj installation methods.
 */
class InstallMethodTest {
    @Test
    fun `Homebrew has correct install command`() {
        InstallMethod.Homebrew.installCommand shouldBe "brew install jj"
        InstallMethod.Homebrew.upgradeCommand shouldBe "brew upgrade jj"
        InstallMethod.Homebrew.name shouldBe "Homebrew"
    }

    @Test
    fun `Cargo has correct install command`() {
        InstallMethod.Cargo.installCommand shouldContain "cargo install"
        InstallMethod.Cargo.installCommand shouldContain "jj-cli"
        InstallMethod.Cargo.name shouldBe "Cargo"
    }

    @Test
    fun `Scoop has correct install command`() {
        InstallMethod.Scoop.installCommand shouldContain "scoop install"
        InstallMethod.Scoop.name shouldBe "Scoop"
    }

    @Test
    fun `Chocolatey has correct install command`() {
        InstallMethod.Chocolatey.installCommand shouldContain "choco install"
        InstallMethod.Chocolatey.name shouldBe "Chocolatey"
    }

    @Test
    fun `Winget has correct install command`() {
        InstallMethod.Winget.installCommand shouldContain "winget install"
        InstallMethod.Winget.name shouldBe "Winget"
    }

    @Test
    fun `Snap has correct install command`() {
        InstallMethod.Snap.installCommand shouldBe "snap install jj"
        InstallMethod.Snap.name shouldBe "Snap"
    }

    @Test
    fun `APT has correct install command`() {
        InstallMethod.Apt.installCommand shouldContain "apt install"
        InstallMethod.Apt.name shouldBe "APT"
    }

    @Test
    fun `Homebrew can run directly`() {
        InstallMethod.Homebrew.canRunDirectly shouldBe true
    }

    @Test
    fun `other methods cannot run directly`() {
        InstallMethod.Cargo.canRunDirectly shouldBe false
        InstallMethod.Scoop.canRunDirectly shouldBe false
        InstallMethod.Manual.canRunDirectly shouldBe false
    }

    @Test
    fun `paths skips invalid PATH entries`() {
        val entries = listOf("/path/with\u0000null", "/valid/path", "", "  ")
        val paths = entries.paths
        paths shouldHaveSize 1
        paths[0].toString() shouldBe "/valid/path"
    }
}
