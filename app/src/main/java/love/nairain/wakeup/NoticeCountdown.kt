package love.nairain.wakeup

internal class NoticeCountdown(private val shownAt: Long) {
    fun ready(now: Long): Boolean = now - shownAt >= ModuleUsageNotice.WAIT_MS
    fun secondsRemaining(now: Long): Long =
        ((ModuleUsageNotice.WAIT_MS - (now - shownAt)).coerceAtLeast(0) + 999L) / 1000L
}
