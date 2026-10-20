package love.nairain.wakeup

import io.github.libxposed.api.XposedInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Executable
import java.lang.reflect.Proxy

class HookTargetsTest {
    private class Fixture {
        fun first() = true
        fun second() = false
        fun third() = 0
        fun fourth() = 1
    }

    private class Framework {
        val active = linkedSetOf<Executable>()
        val removed = mutableListOf<Executable>()
        val warnings = mutableListOf<String>()
        var reject: Executable? = null
        val module = proxy<XposedInterface> { name, args ->
            when (name) {
                "hook" -> {
                    val method = args[0] as Executable
                    proxy<XposedInterface.HookBuilder> { operation, _ ->
                        check(operation == "intercept")
                        check(method != reject) { "模拟框架拒绝安装" }
                        active += method
                        proxy<XposedInterface.HookHandle> { handleOperation, _ ->
                            check(handleOperation == "unhook")
                            active -= method
                            removed += method
                            null
                        }
                    }
                }
                "log" -> { if (args[0] == 5) warnings += args[2] as String; null }
                else -> error("未预期的框架调用：$name")
            }
        }
        val targets = HookTargets(module, StartupStatus(module))
    }

    @Test
    fun missingDependencySkipsWholeGroupAndKeepsOtherFeatures() {
        val framework = Framework()
        val first = Fixture::class.java.getDeclaredMethod("first")
        framework.targets.put(HookPoint.DARK_GET, first)
        var entered = false
        framework.targets.install("模式保存", HookPoint.DARK_GET, HookPoint.DARK_SET) {
            entered = true
            hook(first) { true }
        }
        framework.targets.install("独立功能", HookPoint.DARK_GET) { hook(first) { true } }
        assertFalse(entered)
        assertEquals(setOf(first), framework.active)
        assertTrue(framework.warnings.any { "缺少定位结果" in it })
    }

    @Test
    fun installationFailureRollsBackOnlyItsOwnGroupInReverseOrder() {
        val framework = Framework()
        val first = Fixture::class.java.getDeclaredMethod("first")
        val second = Fixture::class.java.getDeclaredMethod("second")
        val third = Fixture::class.java.getDeclaredMethod("third")
        val fourth = Fixture::class.java.getDeclaredMethod("fourth")
        framework.targets.put(HookPoint.VIP, first)
        framework.targets.put(HookPoint.COLD_AD, second)
        framework.targets.put(HookPoint.HOT_AD, third)
        framework.targets.install("独立功能", HookPoint.VIP) { hook(first) { true } }
        framework.reject = fourth
        framework.targets.install("依赖组", HookPoint.COLD_AD, HookPoint.HOT_AD) {
            hook(second) { false }
            hook(third) { 0 }
            hook(fourth) { 1 }
        }
        assertEquals(listOf(third, second), framework.removed)
        assertEquals(setOf(first), framework.active)
        assertTrue(framework.warnings.any { "依赖组" in it })
        framework.targets.install("后续功能", HookPoint.HOT_AD) { hook(third) { 0 } }
        assertEquals(setOf(first, third), framework.active)
    }

    @Test
    fun successfulGroupKeepsEveryHook() {
        val framework = Framework()
        val first = Fixture::class.java.getDeclaredMethod("first")
        val second = Fixture::class.java.getDeclaredMethod("second")
        framework.targets.put(HookPoint.DARK_GET, first)
        framework.targets.put(HookPoint.DARK_SET, second)
        framework.targets.install("模式保存", HookPoint.DARK_GET, HookPoint.DARK_SET) {
            hook(first) { true }
            hook(second) { false }
        }
        assertEquals(setOf(first, second), framework.active)
        assertTrue(framework.removed.isEmpty())
        assertTrue(framework.warnings.isEmpty())
    }

    companion object {
        private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                invoke(method.name, args.orEmpty())
            } as T
    }
}
