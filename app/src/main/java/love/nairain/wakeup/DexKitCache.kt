package love.nairain.wakeup

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

internal class DexKitCache(dataDir: String) {
    val file = File(dataDir, "files/love.nairain.wakeup/dexkit-cache.bin")
    private val atomic = AtomicFile(file)

    fun read(fingerprint: String): ScanSnapshot? {
        if (!file.exists() && !File("${file.path}.bak").exists()) return null
        require(file.length() <= 2 * 1024 * 1024) { "缓存文件过大" }
        return atomic.openRead().use(ScanSnapshotCodec::read).takeIf { it.fingerprint == fingerprint }
    }

    fun write(snapshot: ScanSnapshot) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建缓存目录" }
        val stream = atomic.startWrite()
        try {
            ScanSnapshotCodec.write(snapshot, stream)
            stream.fd.sync()
            atomic.finishWrite(stream)
            check(atomic.openRead().use(ScanSnapshotCodec::read) == snapshot) { "缓存写入校验失败" }
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }
}

internal object ApkFingerprint {
    const val RULES_VERSION = 3

    fun calculate(hostPaths: List<String>, modulePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${ScanSnapshotCodec.VERSION}:$RULES_VERSION:${TargetApp.PACKAGE_NAME}".toByteArray())
        // 固定角色和边界，避免拆分 APK 顺序变化或文件拼接产生相同标识。
        val inputs = listOf("module" to modulePath, "base" to hostPaths.first()) +
            hostPaths.drop(1).sorted().map { "split:${File(it).name}" to it }
        inputs.forEach { (role, path) ->
            val file = File(path)
            digest.update("\u0000$role:${file.length()}\u0000".toByteArray())
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
