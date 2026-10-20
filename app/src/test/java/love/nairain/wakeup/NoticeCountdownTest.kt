package love.nairain.wakeup

import org.junit.Assert.*
import org.junit.Test

class NoticeCountdownTest {
    @Test fun fiveFullSecondsMustPassBeforeConfirmation() {
        val timer = NoticeCountdown(1000)
        assertEquals(5L, timer.secondsRemaining(1000))
        assertFalse(timer.ready(5999))
        assertEquals(1L, timer.secondsRemaining(5999))
        assertTrue(timer.ready(6000))
        assertEquals(0L, timer.secondsRemaining(6000))
    }

    @Test fun redisplayedNoticeStartsANewCountdown() {
        assertTrue(NoticeCountdown(1000).ready(7000))
        assertFalse(NoticeCountdown(7000).ready(7000))
        assertTrue(NoticeCountdown(7000).ready(12000))
    }
}
