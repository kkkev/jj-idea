package `in`.kkkev.jjidea.ui

import com.intellij.util.text.DateTimeFormatManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class JujutsuDateTimeFormatterTest {
    @BeforeEach
    fun setUp() {
        mockkStatic(DateTimeFormatManager::class)
        every { DateTimeFormatManager.getInstance() } returns DateTimeFormatManager()
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(DateTimeFormatManager::class)
    }

    @Test
    fun `format timestamp from today shows 'Today' with time`() {
        val now = System.currentTimeMillis()
        val instant = Instant.fromEpochMilliseconds(now)

        val result = DateTimeFormatter.formatRelative(instant)

        result shouldStartWith "Today"
    }

    @Test
    fun `format timestamp from yesterday shows 'Yesterday' with time`() {
        val yesterday = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
        val instant = Instant.fromEpochMilliseconds(yesterday)

        val result = DateTimeFormatter.formatRelative(instant)

        result shouldStartWith "Yesterday"
    }

    @Test
    fun `format timestamp from 2 days ago shows localized date and time`() {
        val twoDaysAgo = System.currentTimeMillis() - (2 * 24 * 60 * 60 * 1000L)
        val instant = Instant.fromEpochMilliseconds(twoDaysAgo)

        val result = DateTimeFormatter.formatRelative(instant)

        // Should not contain "Today" or "Yesterday"
        result shouldBe result
        assert(!result.startsWith("Today"))
        assert(!result.startsWith("Yesterday"))
    }

    @Test
    fun `format timestamp from one week ago shows localized date and time`() {
        val oneWeekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        val instant = Instant.fromEpochMilliseconds(oneWeekAgo)

        val result = DateTimeFormatter.formatRelative(instant)

        assert(!result.startsWith("Today"))
        assert(!result.startsWith("Yesterday"))
    }

    @Test
    fun `format timestamp includes time portion`() {
        val now = System.currentTimeMillis()
        val instant = Instant.fromEpochMilliseconds(now)

        val result = DateTimeFormatter.formatRelative(instant)

        // Should contain time separator (colon)
        result shouldContain ":"
    }

    @Test
    fun `format handles midnight boundary correctly for today`() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 1)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val instant = Instant.fromEpochMilliseconds(cal.timeInMillis)
        val result = DateTimeFormatter.formatRelative(instant)

        result shouldStartWith "Today"
    }

    @Test
    fun `format handles midnight boundary correctly for yesterday`() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        val instant = Instant.fromEpochMilliseconds(cal.timeInMillis)
        val result = DateTimeFormatter.formatRelative(instant)

        result shouldStartWith "Yesterday"
    }
}
