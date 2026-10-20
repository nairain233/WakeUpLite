package love.nairain.wakeup

import org.junit.Assert.*
import org.junit.Test
import org.luckypray.dexkit.wrap.DexMethod
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Proxy

class CachedTargetsTest {
    class Fixture(val value: Int) {
        fun values(input: Array<String>, enabled: Boolean): Array<String> = if (enabled) input else emptyArray()
        companion object { @JvmStatic fun check(value: Int) = value > 0 }
    }

    @Test fun restoresArraySignatureWithoutNativeDexKit() {
        val method = Fixture::class.java.getDeclaredMethod("values", Array<String>::class.java, Boolean::class.javaPrimitiveType)
        val loaded = CachedTargets.reflect(MethodReference(DexMethod(method).toString(), false), checkNotNull(Fixture::class.java.classLoader))
        assertEquals(method, loaded)
    }

    @Test fun restoresConstructorAndStaticCallerWithoutNativeDexKit() {
        val constructor = Fixture::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
        val method = Fixture::class.java.getDeclaredMethod("check", Int::class.javaPrimitiveType)
        assertEquals(constructor, CachedTargets.reflect(MethodReference(DexMethod(constructor).toString(), false), checkNotNull(Fixture::class.java.classLoader)))
        assertEquals(method, CachedTargets.reflect(MethodReference(DexMethod(method).toString(), true), checkNotNull(Fixture::class.java.classLoader)))
    }

    @Test fun rejectsChangedSignatureOrStaticFlag() {
        val method = Fixture::class.java.getDeclaredMethod("check", Int::class.javaPrimitiveType)
        val descriptor = DexMethod(method).toString()
        assertThrows(IllegalStateException::class.java) {
            CachedTargets.reflect(MethodReference(descriptor, false), checkNotNull(Fixture::class.java.classLoader))
        }
        assertThrows(Exception::class.java) {
            CachedTargets.reflect(MethodReference(descriptor.replace("(I)Z", "(J)Z"), true), checkNotNull(Fixture::class.java.classLoader))
        }
    }

    @Test fun partialSnapshotRestoresOnlySuccessesAndCallersWithoutInstallingHooks() {
        val method = Fixture::class.java.getDeclaredMethod("check", Int::class.javaPrimitiveType)
        val constructor = Fixture::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
        val reference = MethodReference(DexMethod(method).toString(), true)
        val snapshot = ScanSnapshot("hash", "generation", BootstrapPhase.NOTICE,
            HookPoint.entries.map { point ->
                if (point == HookPoint.VIP) PointResult(point, reference) else PointResult(point, error = "未找到")
            }, listOf(MethodReference(DexMethod(constructor).toString(), false)), emptyList(), "old-process")
        val module = Proxy.newProxyInstance(XposedInterface::class.java.classLoader,
            arrayOf(XposedInterface::class.java)) { _, called, _ ->
            error("恢复缓存期间不应调用框架：${called.name}")
        } as XposedInterface
        val (targets, callers) = CachedTargets.restore(snapshot, checkNotNull(Fixture::class.java.classLoader),
            module, StartupStatus(module))
        assertEquals(1, targets.size)
        assertEquals(method, targets[HookPoint.VIP])
        assertEquals(listOf(constructor), callers)
    }
}
