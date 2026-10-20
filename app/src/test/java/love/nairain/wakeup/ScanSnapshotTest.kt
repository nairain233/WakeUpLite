package love.nairain.wakeup

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ScanSnapshotTest {
    private fun snapshot(successes: Int) = ScanSnapshot("apk-hash", "scan-generation", BootstrapPhase.RESULT,
        HookPoint.entries.mapIndexed { index, point ->
            if (index < successes) PointResult(point, MethodReference("Lsample/Host;->m$index()Z", true))
            else PointResult(point, error = "未找到")
        }, listOf(MethodReference("Lsample/Host;-><init>()V", false)), emptyList())

    private fun roundTrip(value: ScanSnapshot): ScanSnapshot {
        val stream = ByteArrayOutputStream()
        ScanSnapshotCodec.write(value, stream)
        return ScanSnapshotCodec.read(ByteArrayInputStream(stream.toByteArray()))
    }

    @Test fun fullResultRemainsNormalAfterSerialization() {
        val value = snapshot(HookPoint.entries.size)
        assertEquals(value, roundTrip(value))
        assertTrue(roundTrip(value).normal)
    }

    @Test fun partialAndEmptyResultsPreserveOnlyResolvedMethods() {
        listOf(0, 3, HookPoint.entries.size - 1).forEach { count ->
            val value = roundTrip(snapshot(count))
            assertEquals(count, value.successful)
            assertFalse(value.normal)
            assertEquals(HookPoint.entries.size - count, value.results.count { it.error == "未找到" })
        }
    }

    @Test fun callerFailuresKeepResultAbnormalAndPathsIntact() {
        val value = snapshot(HookPoint.entries.size).copy(issues = listOf(ScanIssue("Lsample/Host;->caller()V", "参数类型缺失")))
        assertEquals(value, roundTrip(value))
        assertFalse(roundTrip(value).normal)
    }

    @Test fun exitingWithoutConfirmationReturnsToReport() {
        assertEquals(StartupRoute.RESULT, roundTrip(snapshot(2)).route("another-process"))
    }

    @Test fun noticeCannotAppearOrBeAcceptedBeforeProcessRestart() {
        val value = roundTrip(snapshot(2).confirmRestart("scanning-process"))
        assertEquals(StartupRoute.RESTART, value.route("scanning-process"))
        assertThrows(IllegalStateException::class.java) { value.acceptNotice("scanning-process") }
        assertEquals(StartupRoute.NOTICE, value.route("new-process"))
    }

    @Test fun acceptedNoticeIsNotRepeatedOnLaterStarts() {
        val value = snapshot(2).confirmRestart("old").acceptNotice("new")
        assertEquals(StartupRoute.READY, roundTrip(value).route("third-process"))
    }

    @Test fun truncatedOrWrongVersionCacheIsRejected() {
        val output = ByteArrayOutputStream()
        ScanSnapshotCodec.write(snapshot(2), output)
        val bytes = output.toByteArray()
        assertThrows(Exception::class.java) { ScanSnapshotCodec.read(ByteArrayInputStream(bytes.copyOf(12))) }
        bytes[7] = 99
        assertThrows(IllegalArgumentException::class.java) { ScanSnapshotCodec.read(ByteArrayInputStream(bytes)) }
    }

    @Test fun duplicateOrConflictingPointStateIsRejected() {
        val value = snapshot(1)
        assertThrows(IllegalArgumentException::class.java) {
            roundTrip(value.copy(results = value.results.dropLast(1) + value.results.first()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            roundTrip(value.copy(results = value.results.map { it.copy(error = "错误") }))
        }
    }

    @Test fun reportPathsUseTechnicalConditionsRatherThanFeatureLabels() {
        HookPoint.entries.forEach { point ->
            assertTrue(point.queryPath.contains("->") || point.queryPath.contains("→"))
            assertFalse(point.queryPath.contains(point.label))
        }
    }
}
