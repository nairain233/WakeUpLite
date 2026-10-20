package love.nairain.wakeup

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkFingerprintTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun hostContentChangeInvalidatesEvenWithSameLengthAndTimestamp() {
        val host = directory.newFile("host.apk").apply { writeText("aaaa") }
        val module = directory.newFile("module.apk").apply { writeText("module") }
        val time = host.lastModified()
        val first = ApkFingerprint.calculate(listOf(host.path), module.path)
        host.writeText("bbbb")
        host.setLastModified(time)
        assertNotEquals(first, ApkFingerprint.calculate(listOf(host.path), module.path))
    }

    @Test fun splitOrderIsStableButModuleAndSplitChangesInvalidate() {
        val base = directory.newFile("base.apk").apply { writeText("base") }
        val first = directory.newFile("a.apk").apply { writeText("a") }
        val second = directory.newFile("b.apk").apply { writeText("b") }
        val module = directory.newFile("module.apk").apply { writeText("module") }
        val paths = listOf(base.path, first.path, second.path)
        val initial = ApkFingerprint.calculate(paths, module.path)
        assertEquals(initial, ApkFingerprint.calculate(listOf(base.path, second.path, first.path), module.path))
        module.writeText("update")
        val updated = ApkFingerprint.calculate(paths, module.path)
        assertNotEquals(initial, updated)
        second.writeText("c")
        assertNotEquals(updated, ApkFingerprint.calculate(paths, module.path))
    }
}
